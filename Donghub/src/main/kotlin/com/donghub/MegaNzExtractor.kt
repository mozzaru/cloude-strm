package com.donghub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MegaNzExtractor : ExtractorApi() {
    override val name            = "Mega"
    override val mainUrl         = "https://mega.nz"
    override val requiresReferer = false

    companion object {
        private const val TAG      = "MegaNzExtractor"
        private const val MEGA_API = "https://g.api.mega.co.nz/cs"
        
        // Chunk 32KB - small enough for ExoPlayer to get data quickly
        private const val CHUNK_SIZE = 32 * 1024

        // Max CDN fetch per seek - 8MB enough for initial prefetch
        private const val MAX_CDN_FETCH = 8L * 1024 * 1024

        private val httpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(true)
                .callTimeout(60, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        // 8 threads for concurrent client handling
        private val proxyExecutor by lazy {
            Executors.newFixedThreadPool(8) { r ->
                Thread(r, "mega-proxy-${System.nanoTime() % 100}").also {
                    it.isDaemon = true
                }
            }
        }

        // Active proxies list for cleanup
        private val activeProxies = mutableListOf<MegaStreamProxy>()

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
                val b = ByteBuffer.allocate(v.size * 4)
                v.forEach { b.putInt(it) }
                return b.array()
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
        Log.d(TAG, "Normalizing URL: $url")
        var u = url.trim().replace("$mainUrl/embed/", "$mainUrl/file/")
        u = u.replace(Regex("""#!([^!]+)!(.+)"""), "/file/$1#$2")
        Log.d(TAG, "Normalized to: $u")
        return u
    }

    private fun parseUrl(url: String): Pair<String, String>? {
        val norm    = normaliseUrl(url)
        val path    = norm.removePrefix("$mainUrl/file/")
        val nodeId  = path.substringBefore("#")
        val fileKey = path.substringAfter("#", "")
        
        Log.d(TAG, "Parse result - nodeId: ${nodeId.take(20)}..., fileKey: ${fileKey.take(20)}...")
        
        return if (nodeId.isBlank() || fileKey.isBlank()) {
            Log.e(TAG, "Cannot parse nodeId or fileKey")
            null
        } else {
            Pair(nodeId, fileKey)
        }
    }

    private suspend fun getFileInfo(nodeId: String): JsonObject? = try {
        Log.d(TAG, "Fetching file info for: $nodeId")
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
        
        Log.d(TAG, "API response: ${resp.text.take(200)}")
        
        val arr = Json.parseToJsonElement(resp.text).jsonArray
        if (arr.isNotEmpty() && arr[0] is JsonObject) {
            Log.i(TAG, "File info retrieved successfully")
            arr[0].jsonObject
        } else {
            Log.w(TAG, "Empty or invalid API response")
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "getFileInfo error: ${e.message}")
        null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.i(TAG, "=== getUrl called ===")
        Log.d(TAG, "URL: $url")
        Log.d(TAG, "Referer: $referer")

        val (nodeId, fileKey) = parseUrl(url) ?: run {
            Log.e(TAG, "Cannot parse Mega URL: $url")
            return
        }

        Log.i(TAG, "Node ID: ${nodeId}")
        Log.d(TAG, "File Key: ${fileKey.take(20)}...")

        val (aesKey, ctrIv) = try {
            decodeFileKey(fileKey)
        } catch (e: Exception) {
            Log.e(TAG, "decodeFileKey failed: ${e.message}")
            return
        }
        
        Log.d(TAG, "AES key decoded successfully")

        Log.d(TAG, "Calling getFileInfo...")
        val finfo = getFileInfo(nodeId) ?: run {
            Log.w(TAG, "API failed -> raw fallback")
            val fallbackUrl = normaliseUrl(url)
            Log.i(TAG, "Returning fallback URL: $fallbackUrl")
            callback.invoke(newExtractorLink(
                source = name, 
                name = name,
                url = fallbackUrl, 
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = "$mainUrl/"
            })
            return
        }

        val cdnUrl   = finfo["g"]?.jsonPrimitive?.contentOrNull ?: run {
            Log.e(TAG, "No CDN URL in response!")
            Log.d(TAG, "Response keys: ${finfo.keys.joinToString()}")
            return
        }
        
        val encAt    = finfo["at"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val attrs    = if (encAt.isNotBlank()) decryptAttrs(encAt, aesKey) else null
        val fileName = attrs?.get("n").orEmpty().ifBlank { "video.mp4" }
        val fileSize = finfo["s"]?.jsonPrimitive?.longOrNull ?: -1L
        val ext      = fileName.substringAfterLast(".", "mp4").lowercase()
        val quality  = guessQuality(fileName, fileSize)
        val label    = qualityLabel(quality)

        Log.i(TAG, "File: '$fileName'  size=${fileSize / 1024 / 1024} MB  ext=$ext  quality=$label")
        Log.d(TAG, "CDN URL: ${cdnUrl.take(80)}...")

        // Stop all old proxies
        synchronized(activeProxies) {
            activeProxies.toList().forEach { it.stop() }
            activeProxies.clear()
        }

        Log.d(TAG, "Starting proxy server...")
        val proxy = MegaStreamProxy(
            nodeId   = nodeId,
            cdnUrl   = cdnUrl,
            aesKey   = aesKey,
            ctrIv    = ctrIv,
            fileSize = fileSize,
            ext      = ext
        )
        val port = proxy.start()

        synchronized(activeProxies) { activeProxies.add(proxy) }

        val playUrl = "http://127.0.0.1:$port/video.$ext"
        Log.i(TAG, "Proxy started on port $port -> $playUrl")

        callback.invoke(
            newExtractorLink(
                source = name,
                name   = name,
                url    = playUrl,
                type   = ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
                this.referer = ""
            }
        )
        
        Log.i(TAG, "=== Mega extraction completed ===")
    }

    private inner class MegaStreamProxy(
        private val nodeId   : String,
        @Volatile private var cdnUrl: String,
        private val aesKey   : ByteArray,
        private val ctrIv    : ByteArray,
        private val fileSize : Long,
        private val ext      : String
    ) {
        private var serverSocket: ServerSocket? = null
        private val stopped = AtomicBoolean(false)
        
        fun start(): Int {
            var port = 0
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                Log.d(TAG, "Server socket created on port $port")
                
                proxyExecutor.execute {
                    acceptLoop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start failed: ${e.message}")
            }
            return port
        }
        
        fun stop() {
            stopped.set(true)
            try {
                serverSocket?.close()
            } catch (_: Exception) {}
        }
        
        private fun acceptLoop() {
            while (!stopped.get()) {
                try {
                    val client = serverSocket!!.accept()
                    proxyExecutor.execute { handleClient(client) }
                } catch (e: Exception) {
                    if (!stopped.get()) Log.w(TAG, "Accept failed: ${e.message}")
                    break
                }
            }
        }
        
        private fun handleClient(client: java.net.Socket) {
            try {
                val input  = client.getInputStream().bufferedReader()
                val output = BufferedOutputStream(client.getOutputStream())
                
                val requestLine = input.readLine() ?: return
                Log.d(TAG, "Request: $requestLine")
                
                val headers = mutableMapOf<String, String>()
                var line: String?
                while (input.readLine().also { line = it }?.isNotEmpty() == true) {
                    val parts = line!!.split(":", limit = 2)
                    if (parts.size == 2) headers[parts[0].trim().lowercase()] = parts[1].trim()
                }
                
                val rangeHeader = headers["range"]
                val (rangeStart, rangeEnd) = parseRange(rangeHeader, fileSize)
                val contentLength = if (fileSize > 0 && rangeEnd >= 0)
                    rangeEnd - rangeStart + 1 else -1L

                Log.d(TAG, "Range: $rangeStart-$rangeEnd  len=$contentLength")
                streamFromCdn(output, rangeHeader, rangeStart, rangeEnd, contentLength)
            } catch (e: Exception) {
                Log.w(TAG, "handleClient error: ${e.message}")
            }
        }

        private fun streamFromCdn(
            output        : BufferedOutputStream,
            rangeHeader   : String?,
            rangeStart    : Long,
            rangeEnd      : Long,
            contentLength : Long
        ) {
            val blockStart  = rangeStart / 16
            val blockOffset = (rangeStart % 16).toInt()
            val cdnFrom     = blockStart * 16
            val adjustedIv  = incrementIv(ctrIv, blockStart)

            // Jika rangeEnd eksplisit (ExoPlayer probe/seek), align ke block
            // boundary berikutnya saja — jangan tambah MAX_CDN_FETCH ekstra
            // karena itu menyebabkan CDN kirim data jauh melebihi yang
            // dibutuhkan dan ExoPlayer sering disconnect → DECODER_INIT_FAILED.
            // Jika open-ended (rangeEnd <= 0), pakai MAX_CDN_FETCH sebagai batas.
            val cdnTo = if (rangeEnd > 0) {
                val aligned = ((rangeEnd / 16) + 1) * 16 - 1
                if (fileSize > 0) aligned.coerceAtMost(fileSize - 1) else aligned
            } else {
                val openEnd = cdnFrom + MAX_CDN_FETCH - 1
                if (fileSize > 0) openEnd.coerceAtMost(fileSize - 1) else openEnd
            }
            val cdnRangeHdr = "bytes=$cdnFrom-$cdnTo"

            Log.d(TAG, "CDN range -> $cdnRangeHdr  (blockOffset=$blockOffset)")

            val cdnReq = Request.Builder()
                .url(cdnUrl)
                .header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; K) " +
                    "AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Origin",  "https://mega.nz")
                .header("Referer", "https://mega.nz/")
                .header("Range",   cdnRangeHdr)
                .build()

            var cdnResp = try {
                httpClient.newCall(cdnReq).execute()
            } catch (e: Exception) {
                Log.w(TAG, "CDN attempt 1 failed: ${e.message}, retrying...")
                Thread.sleep(1000)
                try {
                    httpClient.newCall(cdnReq).execute()
                } catch (e2: Exception) {
                    Log.e(TAG, "CDN attempt 2 failed: ${e2.message}")
                    sendError(output, 502)
                    return
                }
            }

            Log.d(TAG, "CDN response: ${cdnResp.code}")

            if (cdnResp.code == 403) {
                Log.w(TAG, "CDN returned 403 (URL expired), re-fetching from Mega API...")
                cdnResp.close()
                // Re-fetch fresh CDN URL from Mega API
                val freshInfo = try {
                    val resp = httpClient.newCall(
                        Request.Builder()
                            .url("$MEGA_API?id=1")
                            .post("""[{"a":"g","g":1,"p":"$nodeId"}]"""
                                .toRequestBody("application/json".toMediaType()))
                            .header("Content-Type", "application/json")
                            .header("Origin",  "https://mega.nz")
                            .header("Referer", "https://mega.nz/")
                            .build()
                    ).execute()
                    val arr = Json.parseToJsonElement(resp.body!!.string()).jsonArray
                    if (arr.isNotEmpty() && arr[0] is JsonObject) arr[0].jsonObject else null
                } catch (e: Exception) {
                    Log.e(TAG, "Re-fetch CDN URL failed: ${e.message}")
                    null
                }
                val freshCdnUrl = freshInfo?.get("g")?.jsonPrimitive?.contentOrNull
                if (freshCdnUrl == null) {
                    Log.e(TAG, "Cannot get fresh CDN URL, giving up")
                    sendError(output, 503)
                    return
                }
                Log.i(TAG, "Got fresh CDN URL, retrying request")
                cdnUrl = freshCdnUrl  // update for future requests too
                val retryReq = Request.Builder()
                    .url(freshCdnUrl)
                    .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; K) " +
                        "AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
                    .header("Origin",  "https://mega.nz")
                    .header("Referer", "https://mega.nz/")
                    .header("Range",   cdnRangeHdr)
                    .build()
                cdnResp = try {
                    httpClient.newCall(retryReq).execute()
                } catch (e: Exception) {
                    Log.e(TAG, "Retry with fresh CDN URL failed: ${e.message}")
                    sendError(output, 502)
                    return
                }
                if (cdnResp.code !in listOf(200, 206)) {
                    Log.e(TAG, "Fresh CDN URL also returned ${cdnResp.code}")
                    sendError(output, 502)
                    cdnResp.close()
                    return
                }
            } else if (cdnResp.code !in listOf(200, 206)) {
                Log.e(TAG, "CDN returned ${cdnResp.code}, giving up")
                sendError(output, 502)
                cdnResp.close()
                return
            }

            val body = cdnResp.body ?: run {
                Log.e(TAG, "CDN body null")
                sendError(output, 502)
                return
            }

            val cdnIsPartial = cdnResp.code == 206
            val isPartial    = rangeHeader != null && cdnIsPartial
            sendResponseHeaders(output,
                if (isPartial) 206 else 200,
                contentLength, rangeStart, rangeEnd, fileSize, isPartial)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                IvParameterSpec(adjustedIv)
            )

            val encStream = body.byteStream()
            val buf       = ByteArray(CHUNK_SIZE)
            var toSkip    = blockOffset
            var remaining = contentLength
            var totalSent = 0L

            var bytesSinceFlush = 0L
            val FLUSH_EVERY     = 256 * 1024L

            try {
                while (remaining != 0L) {
                    val want = when {
                        remaining > 0 -> minOf(buf.size.toLong(), remaining + toSkip).toInt()
                        else          -> buf.size
                    }
                    val n = encStream.read(buf, 0, want)
                    if (n <= 0) break

                    val dec = cipher.update(buf, 0, n) ?: continue
                    if (dec.isEmpty()) continue

                    val writeFrom: Int
                    val writeData: ByteArray
                    if (toSkip > 0) {
                        val skip = minOf(toSkip, dec.size)
                        toSkip -= skip
                        if (skip >= dec.size) continue
                        writeFrom = skip
                        writeData = dec
                    } else {
                        writeFrom = 0
                        writeData = dec
                    }

                    val available = dec.size - writeFrom
                    val toWrite   = if (remaining > 0)
                        minOf(available.toLong(), remaining).toInt()
                    else available

                    if (toWrite <= 0) continue

                    output.write(writeData, writeFrom, toWrite)
                    totalSent       += toWrite
                    bytesSinceFlush += toWrite
                    if (remaining > 0) remaining -= toWrite

                    if (bytesSinceFlush >= FLUSH_EVERY) {
                        output.flush()
                        bytesSinceFlush = 0
                    }
                }

                output.flush()
                Log.d(TAG, "Stream done - sent ${totalSent / 1024} KB")

            } catch (e: java.io.IOException) {
                Log.d(TAG, "Client disconnected after ${totalSent / 1024} KB: ${e.message}")
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
            out: BufferedOutputStream, statusCode: Int, contentLength: Long,
            rangeStart: Long, rangeEnd: Long, total: Long, isPartial: Boolean
        ) {
            val mime   = when (ext) {
                "mkv"  -> "video/x-matroska"
                "webm" -> "video/webm"
                else   -> "video/mp4"
            }
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

        private fun sendError(out: BufferedOutputStream, code: Int) {
            try {
                out.write(
                    "HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII)
                )
                out.flush()
            } catch (_: Exception) {}
        }
    }
}
