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

        // FIX: Chunk 32KB — cukup kecil agar ExoPlayer dapat data cepat,
        //      cukup besar agar AES/CTR efisien (harus kelipatan 16 bytes)
        private const val CHUNK_SIZE = 32 * 1024

        // FIX: Batas request CDN per seek — 8MB cukup untuk prefetch awal.
        //      ExoPlayer akan range-request lagi kalau butuh lebih.
        //      Ini mencegah CDN Mega rate-limit koneksi panjang.
        private const val MAX_CDN_FETCH = 8L * 1024 * 1024

        private val httpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(true)
                .callTimeout(60, TimeUnit.SECONDS)   // FIX: turunkan dari 120s
                .readTimeout(30, TimeUnit.SECONDS)   // FIX: turunkan dari 120s
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        // FIX: 8 thread — 1 acceptLoop + hingga 7 concurrent client handler.
        // ExoPlayer bisa buka 2-3 koneksi paralel (prefetch + playback + subtitle).
        // Saat seek, koneksi lama belum tentu langsung tutup → perlu slot ekstra.
        // ThreadFactory diberi nama agar mudah trace di logcat: "mega-proxy-N"
        private val proxyExecutor by lazy {
            Executors.newFixedThreadPool(8) { r ->
                Thread(r, "mega-proxy-${System.nanoTime() % 100}").also {
                    it.isDaemon = true
                }
            }
        }

        // FIX: Simpan list proxy aktif (bukan singleton) agar lama bisa stop
        //      tanpa race condition dengan proxy baru
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
        Log.d(TAG, "Fetching file info: $nodeId")
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
        if (arr.isNotEmpty() && arr[0] is JsonObject) arr[0].jsonObject else null
    } catch (e: Exception) {
        Log.e(TAG, "getFileInfo error: ${e.message}"); null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "getUrl → $url")

        val (nodeId, fileKey) = parseUrl(url) ?: run {
            Log.e(TAG, "Cannot parse Mega URL: $url"); return
        }

        val (aesKey, ctrIv) = try {
            decodeFileKey(fileKey)
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
            Log.e(TAG, "No CDN URL"); return
        }
        val encAt    = finfo["at"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val attrs    = if (encAt.isNotBlank()) decryptAttrs(encAt, aesKey) else null
        val fileName = attrs?.get("n").orEmpty().ifBlank { "video.mp4" }
        val fileSize = finfo["s"]?.jsonPrimitive?.longOrNull ?: -1L
        val ext      = fileName.substringAfterLast(".", "mp4").lowercase()
        val quality  = guessQuality(fileName, fileSize)
        val label    = qualityLabel(quality)

        Log.i(TAG, "File: '$fileName'  size=${fileSize / 1024 / 1024} MB  ext=$ext")

        // FIX: Stop semua proxy lama sebelum buat baru
        // Pakai toList() agar tidak ConcurrentModificationException
        synchronized(activeProxies) {
            activeProxies.toList().forEach { it.stop() }
            activeProxies.clear()
        }

        val proxy = MegaStreamProxy(
            cdnUrl   = cdnUrl,
            aesKey   = aesKey,
            ctrIv    = ctrIv,
            fileSize = fileSize,
            ext      = ext
        )
        val port = proxy.start()

        synchronized(activeProxies) { activeProxies.add(proxy) }

        val playUrl = "http://127.0.0.1:$port/video.$ext"
        Log.i(TAG, "Proxy port $port → $playUrl")

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
    }

    private inner class MegaStreamProxy(
        private val cdnUrl   : String,
        private val aesKey   : ByteArray,
        private val ctrIv    : ByteArray,
        private val fileSize : Long,
        private val ext      : String
    ) {
        private var serverSocket : ServerSocket? = null
        private val running      = AtomicBoolean(true)

        // FIX: pakai bounded executor bersama (shared dari companion object)
        // bukan CachedThreadPool per-proxy yang bisa OOM
        fun start(): Int {
            val ss = ServerSocket(0).also {
                it.reuseAddress = true
                it.soTimeout    = 0
            }
            serverSocket = ss
            proxyExecutor.submit { acceptLoop(ss) }
            Log.d(TAG, "Proxy started on port ${ss.localPort}")
            return ss.localPort
        }

        fun stop() {
            running.set(false)
            try { serverSocket?.close() } catch (_: Exception) {}
            Log.d(TAG, "Proxy stopped")
        }

        private fun acceptLoop(ss: ServerSocket) {
            while (running.get()) {
                try {
                    val client = ss.accept()
                    client.tcpNoDelay  = true
                    // FIX: soTimeout 60s — kalau ExoPlayer disconnect tanpa close,
                    //      thread tidak hang selamanya dan pool tidak exhausted.
                    //      Pool exhausted = semua request baru timeout = ERROR 2001.
                    client.soTimeout   = 60_000
                    proxyExecutor.submit { handleClient(client) }
                } catch (_: Exception) { break }
            }
        }

        private fun handleClient(client: java.net.Socket) {
            try {
                client.use { sock ->
                    val reader = sock.getInputStream().bufferedReader(Charsets.US_ASCII)
                    // FIX: Pakai BufferedOutputStream 64KB — kurangi syscall write()
                    // Lebih cepat daripada flush() tiap chunk kecil
                    val output = BufferedOutputStream(sock.getOutputStream(), 65536)

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
                            0L, if (fileSize > 0) fileSize - 1 else 0L,
                            fileSize, false)
                        output.flush()
                        return
                    }

                    val rangeHeader = headers["range"]
                    val (rangeStart, rangeEnd) = parseRange(rangeHeader, fileSize)
                    val contentLength = if (fileSize > 0 && rangeEnd >= 0)
                        rangeEnd - rangeStart + 1 else -1L

                    Log.d(TAG, "Range: $rangeStart-$rangeEnd  len=$contentLength")
                    streamFromCdn(output, rangeHeader, rangeStart, rangeEnd, contentLength)
                }
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

            // FIX: Cap CDN range ke MAX_CDN_FETCH per request
            // Sebelumnya: selalu minta bytes=cdnFrom-${fileSize-1} (seluruh sisa file)
            // → CDN Mega rate-limit koneksi panjang → buffering
            // ExoPlayer akan Range request lagi untuk chunk berikutnya otomatis
            // FIX: cdnTo harus = rangeEnd (dibulatkan ke batas block 16B) + lookahead 8MB.
            // Formula lama: cdnFrom + blockOffset + contentLength + 8MB
            //   → double-count: cdnFrom sudah = blockStart*16, lalu ditambah blockOffset lagi
            //   → cdnTo bisa LEBIH BESAR dari fileSize → CDN return 416 → ERROR 2001.
            // Formula baru: mulai dari rangeEnd (batas atas yang ExoPlayer minta),
            //   bulatkan ke atas ke kelipatan 16, lalu tambah lookahead MAX_CDN_FETCH.
            val alignedRangeEnd = if (rangeEnd > 0) {
                val nextBlock = ((rangeEnd / 16) + 1) * 16 - 1
                nextBlock
            } else {
                cdnFrom + MAX_CDN_FETCH - 1
            }
            val cdnTo = when {
                fileSize > 0 -> (alignedRangeEnd + MAX_CDN_FETCH).coerceAtMost(fileSize - 1)
                else         -> alignedRangeEnd + MAX_CDN_FETCH
            }
            val cdnRangeHdr = "bytes=$cdnFrom-$cdnTo"

            Log.d(TAG, "CDN range → $cdnRangeHdr  (blockOffset=$blockOffset)")

            val cdnReq = Request.Builder()
                .url(cdnUrl)
                .header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; K) " +
                    "AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Origin",  "https://mega.nz")
                .header("Referer", "https://mega.nz/")
                .header("Range",   cdnRangeHdr)
                .build()

            // FIX: Retry CDN 1x (delay 1s) — network hiccup sesaat tidak langsung
            // return error 502 ke ExoPlayer yang langsung trigger ERROR_CODE 2001.
            var cdnResp = try {
                httpClient.newCall(cdnReq).execute()
            } catch (e: Exception) {
                Log.w(TAG, "CDN attempt 1 failed: ${e.message}, retrying…")
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

            if (cdnResp.code !in listOf(200, 206)) {
                Log.w(TAG, "CDN returned ${cdnResp.code}, retrying once…")
                cdnResp.close()
                Thread.sleep(1000)
                cdnResp = try {
                    httpClient.newCall(cdnReq).execute()
                } catch (e: Exception) {
                    Log.e(TAG, "CDN retry failed: ${e.message}")
                    sendError(output, 502)
                    return
                }
                if (cdnResp.code !in listOf(200, 206)) {
                    Log.e(TAG, "CDN returned ${cdnResp.code} after retry")
                    sendError(output, 502)
                    cdnResp.close()
                    return
                }
            }

            val body = cdnResp.body ?: run {
                Log.e(TAG, "CDN body null")
                sendError(output, 502)
                return
            }

            // FIX: isPartial harus ikut status CDN, bukan hanya ada/tidak Range header.
            // Kalau CDN return 200 (tidak support range), kita juga harus return 200,
            // atau ExoPlayer akan reject Content-Range yang tidak konsisten → ERROR 2001.
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
            // FIX: Buffer 32KB — kelipatan 16 (AES block), cukup kecil agar
            //      ExoPlayer dapat data cepat tanpa nunggu chunk besar selesai
            val buf       = ByteArray(CHUNK_SIZE)
            var toSkip    = blockOffset
            var remaining = contentLength
            var totalSent = 0L

            // FIX: Track flush setiap 256KB — tidak perlu flush tiap 32KB chunk
            //      (BufferedOutputStream sudah handle buffer, flush terlalu sering
            //       justru memperlambat karena banyak syscall)
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

                    // FIX: Flush setiap 256KB bukan setiap chunk
                    if (bytesSinceFlush >= FLUSH_EVERY) {
                        output.flush()
                        bytesSinceFlush = 0
                    }
                }

                // Flush sisa data di buffer
                output.flush()
                Log.d(TAG, "Stream done — sent ${totalSent / 1024} KB")

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
            // Headers langsung flush agar ExoPlayer tidak nunggu
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
