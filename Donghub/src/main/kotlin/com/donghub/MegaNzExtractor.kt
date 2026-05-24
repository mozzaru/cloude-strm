package com.donghub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MegaNzExtractor : ExtractorApi() {
    override val name            = "Mega"
    override val mainUrl         = "https://mega.nz"
    override val requiresReferer = false

    companion object {
        private const val TAG       = "MegaNzExtractor"
        private const val MEGA_API  = "https://g.api.mega.co.nz/cs"

        private val httpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(true)
                .callTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        @Volatile private var activeProxy: MegaStreamProxy? = null

        fun megaB64Decode(s: String): ByteArray {
            val fixed = s.replace("-", "+").replace("_", "/")
            val pad   = (4 - fixed.length % 4) % 4
            return Base64.getDecoder().decode(fixed + "=".repeat(pad))
        }

        fun decodeFileKey(b64key: String): Pair<ByteArray, ByteArray> {
            val raw = megaB64Decode(b64key)
            val buf = ByteBuffer.wrap(raw)
            val k   = IntArray(8) { buf.int }
            fun pack(vararg v: Int): ByteArray {
                val b = ByteBuffer.allocate(v.size * 4); v.forEach { b.putInt(it) }; return b.array()
            }
            val aesKey = pack(k[0] xor k[4], k[1] xor k[5], k[2] xor k[6], k[3] xor k[7])
            val ctrIv  = pack(k[4], k[5], 0, 0)
            return Pair(aesKey, ctrIv)
        }

        fun decryptAttrs(encB64: String, aesKey: ByteArray): Map<String, String>? = try {
            val enc    = megaB64Decode(encB64)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,
                        SecretKeySpec(aesKey, "AES"),
                        IvParameterSpec(ByteArray(16)))
            val dec  = cipher.doFinal(enc)
            val text = String(dec.takeWhile { it != 0.toByte() }.toByteArray(), Charsets.UTF_8)
            if (text.startsWith("MEGA")) {
                val json = Json.parseToJsonElement(text.removePrefix("MEGA"))
                json.jsonObject.mapValues { it.value.jsonPrimitive.content }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "decryptAttrs failed: ${e.message}"); null
        }

        fun incrementIv(iv: ByteArray, delta: Long): ByteArray {
            val result = iv.copyOf()
            var carry  = delta
            for (i in 15 downTo 0) {
                val sum = (result[i].toLong() and 0xFF) + (carry and 0xFF)
                result[i] = sum.toByte()
                carry = (carry ushr 8) + (sum ushr 8)
                if (carry == 0L) break
            }
            return result
        }

        fun guessQuality(name: String, fileSize: Long = -1L): Int {
            val s = name.lowercase()
            return when {
                "4k"   in s || "2160" in s -> Qualities.P2160.value
                "1080" in s                -> Qualities.P1080.value
                "720"  in s                -> Qualities.P720.value
                "480"  in s                -> Qualities.P480.value
                "360"  in s                -> Qualities.P360.value
                fileSize > 600_000_000L    -> Qualities.P1080.value
                fileSize > 200_000_000L    -> Qualities.P720.value
                fileSize > 80_000_000L     -> Qualities.P480.value
                fileSize > 0               -> Qualities.P360.value
                else                       -> Qualities.Unknown.value
            }
        }

        fun qualityLabel(quality: Int): String = when (quality) {
            Qualities.P2160.value -> "2160p"
            Qualities.P1080.value -> "1080p"
            Qualities.P720.value  -> "720p"
            Qualities.P480.value  -> "480p"
            Qualities.P360.value  -> "360p"
            else                  -> "MP4"
        }
    }

    private fun normaliseUrl(url: String): String {
        var u = url.trim().replace("$mainUrl/embed/", "$mainUrl/file/")
        u = u.replace(Regex("""/#!([^!]+)!(.+)"""), "/file/$1#$2")
        return u
    }

    private fun parseUrl(url: String): Pair<String, String>? {
        val norm    = normaliseUrl(url)
        val path    = norm.removePrefix("$mainUrl/file/")
        val nodeId  = path.substringBefore("#")
        val fileKey = path.substringAfter("#", "")
        return if (nodeId.isBlank() || fileKey.isBlank()) null
               else Pair(nodeId, fileKey)
    }

    private suspend fun getFileInfo(nodeId: String): JsonObject? = try {
        Log.d(TAG, "Fetching file info for node: $nodeId")
        val resp = app.post(
            "$MEGA_API?id=1",
            headers     = mapOf(
                "Content-Type" to "application/json",
                "Origin"       to "https://mega.nz",
                "Referer"      to "https://mega.nz/"
            ),
            requestBody = """[{"a":"g","g":1,"p":"$nodeId"}]"""
                            .toRequestBody("application/json".toMediaType())
        )
        val arr = Json.parseToJsonElement(resp.text).jsonArray
        if (arr.isNotEmpty() && arr[0] is JsonObject) {
            Log.d(TAG, "File info fetched OK")
            arr[0].jsonObject
        } else {
            Log.e(TAG, "Unexpected API response: ${resp.text.take(200)}")
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "getFileInfo error: ${e.message}"); null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "getUrl called → $url")

        val (nodeId, fileKey) = parseUrl(url) ?: run {
            Log.e(TAG, "Cannot parse Mega URL: $url"); return
        }
        Log.d(TAG, "Parsed → node=$nodeId  key=${fileKey.take(14)}…")

        val (aesKey, ctrIv) = try {
            decodeFileKey(fileKey).also { Log.d(TAG, "AES key decoded OK") }
        } catch (e: Exception) {
            Log.e(TAG, "decodeFileKey failed: ${e.message}"); return
        }

        val finfo = getFileInfo(nodeId) ?: run {
            Log.w(TAG, "API failed → raw fallback")
            callback.invoke(newExtractorLink(source = name, name = name,
                url = normaliseUrl(url), type = ExtractorLinkType.VIDEO) {
                this.quality = Qualities.Unknown.value
                this.referer = "$mainUrl/"
            })
            return
        }

        val cdnUrl   = finfo["g"]?.jsonPrimitive?.contentOrNull ?: run {
            Log.e(TAG, "No CDN URL in response"); return
        }
        val encAt    = finfo["at"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val attrs    = if (encAt.isNotBlank()) decryptAttrs(encAt, aesKey) else null
        val fileName = attrs?.get("n").orEmpty().ifBlank { "video.mp4" }
        val fileSize = finfo["s"]?.jsonPrimitive?.longOrNull ?: -1L
        val ext      = fileName.substringAfterLast(".", "mp4").lowercase()
        val quality  = guessQuality(fileName, fileSize)
        val label    = qualityLabel(quality)

        Log.i(TAG, "File: '$fileName'  size=${fileSize / 1024 / 1024} MB  ext=$ext  quality=$quality")
        Log.d(TAG, "CDN: ${cdnUrl.take(72)}…")

        // Tidak ada prefetch — langsung start proxy dan callback
        // ExoPlayer akan seek sendiri untuk cari moov atom
        activeProxy?.stop()
        Log.d(TAG, "Old proxy stopped")

        val proxy = MegaStreamProxy(
            cdnUrl   = cdnUrl,
            aesKey   = aesKey,
            ctrIv    = ctrIv,
            fileSize = fileSize,
            ext      = ext
        )
        val port    = proxy.start()
        activeProxy = proxy

        val playUrl = "http://127.0.0.1:$port/video.$ext"
        Log.i(TAG, "Proxy started on port $port → $playUrl")

        // Nama: "Mega" saja (default) agar tidak ada isu label
        callback.invoke(
            newExtractorLink(
                source = name,
                name   = label,
                url    = playUrl,
                type   = ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
                this.referer = ""
            }
        )
        Log.i(TAG, "ExtractorLink delivered to callback ✅")
    }

    private inner class MegaStreamProxy(
        private val cdnUrl   : String,
        private val aesKey   : ByteArray,
        private val ctrIv    : ByteArray,
        private val fileSize : Long,
        private val ext      : String
    ) {
        private var serverSocket : ServerSocket? = null
        private val executor     = Executors.newFixedThreadPool(8)
        @Volatile private var running = true

        fun start(): Int {
            val ss = ServerSocket(0)
            serverSocket = ss
            executor.submit { acceptLoop(ss) }
            Log.d(TAG, "Proxy accept loop started on port ${ss.localPort}")
            return ss.localPort
        }

        fun stop() {
            running = false
            try { serverSocket?.close() } catch (_: Exception) {}
            executor.shutdownNow()
            Log.d(TAG, "Proxy stopped")
        }

        private fun acceptLoop(ss: ServerSocket) {
            while (running) {
                try {
                    val client = ss.accept()
                    Log.d(TAG, "New client: ${client.inetAddress}")
                    executor.submit { handleClient(client) }
                } catch (_: Exception) { break }
            }
        }

        private fun handleClient(client: java.net.Socket) {
            try {
                client.soTimeout = 120_000
                client.use { sock ->
                    val reader = sock.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val output = sock.getOutputStream()

                    val requestLine = reader.readLine() ?: return
                    Log.d(TAG, "Request: $requestLine")

                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) break
                        val idx = line.indexOf(':')
                        if (idx > 0)
                            headers[line.substring(0, idx).trim().lowercase()] =
                                line.substring(idx + 1).trim()
                    }

                    val method = requestLine.substringBefore(" ").uppercase()
                    if (method == "HEAD") {
                        sendResponseHeaders(output, 200, fileSize,
                            0L, if (fileSize > 0) fileSize - 1 else 0L, fileSize, false)
                        return
                    }

                    val rangeHeader            = headers["range"]
                    val (rangeStart, rangeEnd) = parseRange(rangeHeader, fileSize)
                    val contentLength          = if (fileSize > 0 && rangeEnd >= 0)
                        rangeEnd - rangeStart + 1 else -1L

                    Log.d(TAG, "Range: $rangeStart-$rangeEnd  contentLength=$contentLength")
                    streamFromCdn(output, rangeHeader, rangeStart, rangeEnd, contentLength)
                }
            } catch (e: Exception) {
                Log.w(TAG, "handleClient error: ${e.message}")
            }
        }

        private fun streamFromCdn(
            output        : OutputStream,
            rangeHeader   : String?,
            rangeStart    : Long,
            rangeEnd      : Long,
            contentLength : Long
        ) {
            val blockStart  = rangeStart / 16
            val blockOffset = (rangeStart % 16).toInt()
            val cdnFrom     = blockStart * 16
            val adjustedIv  = incrementIv(ctrIv, blockStart)
            val cdnRangeHdr = if (fileSize > 0) "bytes=$cdnFrom-${fileSize - 1}"
                              else              "bytes=$cdnFrom-"

            Log.d(TAG, "AES seek → block=$blockStart  skip=$blockOffset  cdnFrom=$cdnFrom")

            val cdnReq = Request.Builder()
                .url(cdnUrl)
                .header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; K) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Origin",  "https://mega.nz")
                .header("Referer", "https://mega.nz/")
                .header("Range",   cdnRangeHdr)
                .build()

            Log.d(TAG, "CDN request → Range: $cdnRangeHdr")
            val cdnResp = httpClient.newCall(cdnReq).execute()
            Log.d(TAG, "CDN response: ${cdnResp.code}")

            val body = cdnResp.body ?: run {
                sendError(output, 502); return
            }

            val isPartial = rangeHeader != null
            sendResponseHeaders(output,
                if (isPartial) 206 else 200,
                contentLength, rangeStart, rangeEnd, fileSize, isPartial)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,
                        SecretKeySpec(aesKey, "AES"),
                        IvParameterSpec(adjustedIv))

            val encStream = body.byteStream()
            val buf       = ByteArray(65536)
            var toSkip    = blockOffset
            var remaining = contentLength
            var totalSent = 0L

            try {
                while (remaining != 0L) {
                    val want = when {
                        remaining > 0 -> minOf(buf.size.toLong(), remaining + toSkip).toInt()
                        else          -> buf.size
                    }
                    val n = encStream.read(buf, 0, want)
                    if (n <= 0) break

                    val dec = cipher.update(buf, 0, n) ?: continue

                    val writeData: ByteArray
                    if (toSkip > 0) {
                        val from  = toSkip.coerceAtMost(dec.size)
                        toSkip    = maxOf(0, toSkip - dec.size)
                        writeData = if (from < dec.size) dec.copyOfRange(from, dec.size)
                                    else ByteArray(0)
                    } else {
                        writeData = dec
                    }
                    if (writeData.isEmpty()) continue

                    val toWrite = if (remaining > 0)
                        writeData.copyOf(minOf(writeData.size.toLong(), remaining).toInt())
                    else writeData

                    output.write(toWrite)
                    totalSent += toWrite.size
                    if (remaining > 0) remaining -= toWrite.size
                }
                output.flush()
                Log.d(TAG, "Stream done — sent ${totalSent / 1024} KB")
            } catch (_: java.io.IOException) {
                Log.d(TAG, "Client disconnected (seek/close) after ${totalSent / 1024} KB")
            } finally {
                body.close()
                cdnResp.close()
            }
        }

        private fun parseRange(header: String?, size: Long): Pair<Long, Long> {
            if (header == null) return Pair(0L, if (size > 0) size - 1 else -1L)
            val m = Regex("""bytes=(\d*)-(\d*)""").find(header)
                ?: return Pair(0L, if (size > 0) size - 1 else -1L)
            val s = m.groupValues[1].toLongOrNull() ?: 0L
            val e = m.groupValues[2].toLongOrNull() ?: (if (size > 0) size - 1 else -1L)
            return Pair(s, e)
        }

        private fun sendResponseHeaders(
            out: OutputStream, statusCode: Int, contentLength: Long,
            rangeStart: Long, rangeEnd: Long, total: Long, isPartial: Boolean
        ) {
            val mime   = if (ext == "mkv") "video/x-matroska" else "video/mp4"
            val status = if (statusCode == 206) "206 Partial Content" else "200 OK"
            val sb     = StringBuilder()
            sb.append("HTTP/1.1 $status\r\n")
            sb.append("Content-Type: $mime\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Connection: keep-alive\r\n")
            if (contentLength > 0) sb.append("Content-Length: $contentLength\r\n")
            if (isPartial && total > 0)
                sb.append("Content-Range: bytes $rangeStart-$rangeEnd/$total\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            out.flush()
        }

        private fun sendError(out: OutputStream, code: Int) {
            out.write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }
}
