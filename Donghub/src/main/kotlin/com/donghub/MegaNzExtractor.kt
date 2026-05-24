package com.donghub

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MegaNzExtractor : ExtractorApi() {
    override val name            = "Mega"
    override val mainUrl         = "https://mega.nz"
    override val requiresReferer = false

    companion object {
        private const val MEGA_API = "https://g.api.mega.co.nz/cs"

        // Reuse one OkHttpClient for CDN streaming
        private val httpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(true)
                .build()
        }

        // Active proxy port → so we only run one proxy at a time
        @Volatile private var activeProxy: MegaStreamProxy? = null

        fun megaB64Decode(s: String): ByteArray {
            val fixed = s.replace("-", "+").replace("_", "/")
            val pad   = (4 - fixed.length % 4) % 4
            return Base64.getDecoder().decode(fixed + "=".repeat(pad))
        }

        fun decodeFileKey(b64key: String): Triple<ByteArray, ByteArray, ByteArray> {
            val raw = megaB64Decode(b64key)
            val buf = ByteBuffer.wrap(raw)
            val k   = IntArray(8) { buf.int }
            fun pack(v: IntArray): ByteArray {
                val b = ByteBuffer.allocate(v.size * 4); v.forEach { b.putInt(it) }; return b.array()
            }
            val aesKey = pack(intArrayOf(k[0] xor k[4], k[1] xor k[5], k[2] xor k[6], k[3] xor k[7]))
            val ctrIv  = pack(intArrayOf(k[4], k[5], 0, 0))
            return Triple(aesKey, ctrIv, ByteArray(16))
        }

        fun decryptAttrs(encB64: String, aesKey: ByteArray): Map<String, String>? = try {
            val enc    = megaB64Decode(encB64)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(ByteArray(16)))
            val dec  = cipher.doFinal(enc)
            val text = String(dec.takeWhile { it != 0.toByte() }.toByteArray(), Charsets.UTF_8)
            if (text.startsWith("MEGA")) {
                val json = Json.parseToJsonElement(text.removePrefix("MEGA"))
                json.jsonObject.mapValues { it.value.jsonPrimitive.content }
            } else null
        } catch (e: Exception) { println("⚠️ [MegaNz] decryptAttrs: ${e.message}"); null }
    }

    // ── URL helpers ────────────────────────────────────────────────────────

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
        return if (nodeId.isBlank() || fileKey.isBlank()) null else Pair(nodeId, fileKey)
    }

    // ── Mega API ───────────────────────────────────────────────────────────

    private suspend fun getFileInfo(nodeId: String): JsonObject? = try {
        val resp = app.post(
            "$MEGA_API?id=1",
            headers     = mapOf("Content-Type" to "application/json",
                                "Origin"  to "https://mega.nz",
                                "Referer" to "https://mega.nz/"),
            requestBody = """[{"a":"g","g":1,"p":"$nodeId"}]"""
                            .toRequestBody("application/json".toMediaType())
        )
        val arr = Json.parseToJsonElement(resp.text).jsonArray
        if (arr.isNotEmpty() && arr[0] is JsonObject) arr[0].jsonObject else null
    } catch (e: Exception) { println("❌ [MegaNz] API: ${e.message}"); null }

    private fun guessQuality(n: String): Int {
        val s = n.lowercase()
        return when {
            "4k" in s || "2160" in s -> Qualities.P2160.value
            "1080" in s -> Qualities.P1080.value
            "720"  in s -> Qualities.P720.value
            "480"  in s -> Qualities.P480.value
            "360"  in s -> Qualities.P360.value
            else        -> Qualities.Unknown.value
        }
    }

    // ── Main ───────────────────────────────────────────────────────────────

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val (nodeId, fileKey) = parseUrl(url) ?: run {
            println("❌ [MegaNz] Cannot parse: $url"); return
        }
        println("🎯 [MegaNz] node=$nodeId  key=${fileKey.take(14)}…")

        val (aesKey, ctrIv, _) = try {
            decodeFileKey(fileKey)
        } catch (e: Exception) {
            println("❌ [MegaNz] decodeFileKey: ${e.message}"); return
        }

        val finfo = getFileInfo(nodeId)

        if (finfo == null) {
            println("⚠️ [MegaNz] API failed → raw fallback")
            callback.invoke(newExtractorLink(source = name, name = name,
                url = normaliseUrl(url), type = ExtractorLinkType.VIDEO) {
                this.quality = Qualities.Unknown.value
                this.referer = "$mainUrl/"
            }); return
        }

        val cdnUrl = finfo["g"]?.jsonPrimitive?.contentOrNull
        if (cdnUrl.isNullOrBlank()) {
            println("❌ [MegaNz] No CDN URL in response"); return
        }

        val encAt    = finfo["at"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val attrs    = if (encAt.isNotBlank()) decryptAttrs(encAt, aesKey) else null
        val fileName = attrs?.get("n").orEmpty()
        val fileSize = finfo["s"]?.jsonPrimitive?.longOrNull ?: -1L
        val ext      = fileName.substringAfterLast(".", "mp4").lowercase()

        println("✅ [MegaNz] '$fileName'  ${fileSize/1024/1024} MB")

        // Stop old proxy if running
        activeProxy?.stop()

        // Start local decrypt proxy on a free port
        val proxy = MegaStreamProxy(cdnUrl, aesKey, ctrIv, fileSize, ext)
        val port  = proxy.start()
        activeProxy = proxy

        println("🔄 [MegaNz] Local proxy started on port $port")

        callback.invoke(
            newExtractorLink(
                source = name,
                name   = "$name · $fileName",
                url    = "http://127.0.0.1:$port/video.$ext",
                type   = ExtractorLinkType.VIDEO
            ) {
                this.quality = guessQuality(fileName)
                this.referer = ""
            }
        )
    }

    // ── Local streaming decrypt proxy ──────────────────────────────────────
    //
    // Listens on localhost, accepts one TCP connection (ExoPlayer),
    // streams encrypted bytes from Mega CDN, decrypts AES-CTR on the fly,
    // forwards clean bytes to ExoPlayer.
    //
    // Supports HTTP Range requests so ExoPlayer can seek.
    // ──────────────────────────────────────────────────────────────────────

    private inner class MegaStreamProxy(
        private val cdnUrl   : String,
        private val aesKey   : ByteArray,
        private val ctrIv    : ByteArray,
        private val fileSize : Long,
        private val ext      : String
    ) {
        private var serverSocket: ServerSocket? = null
        private val executor = Executors.newCachedThreadPool()
        @Volatile private var running = true

        fun start(): Int {
            val ss = ServerSocket(0)   // OS picks a free port
            serverSocket = ss
            executor.submit { acceptLoop(ss) }
            return ss.localPort
        }

        fun stop() {
            running = false
            try { serverSocket?.close() } catch (_: Exception) {}
            executor.shutdownNow()
        }

        private fun acceptLoop(ss: ServerSocket) {
            while (running) {
                try {
                    val client = ss.accept()
                    executor.submit { handleClient(client) }
                } catch (_: Exception) { break }
            }
        }

        private fun handleClient(client: java.net.Socket) {
            try {
                client.use { sock ->
                    val input  = sock.getInputStream().bufferedReader()
                    val output = sock.getOutputStream()

                    // Read HTTP request line + headers
                    val requestLine = input.readLine() ?: return
                    val headers     = mutableMapOf<String, String>()
                    var line: String
                    while (true) {
                        line = input.readLine() ?: break
                        if (line.isBlank()) break
                        val idx = line.indexOf(':')
                        if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }

                    println("📡 [MegaProxy] $requestLine")

                    // Parse Range header
                    val rangeHeader = headers["range"]
                    val (rangeStart, rangeEnd) = parseRange(rangeHeader, fileSize)

                    // AES-CTR: each 16-byte block is independent once we know
                    // the counter value. Counter = (byteOffset / 16) added to IV.
                    // We can seek by adjusting the IV and skipping partial blocks.
                    val blockStart  = rangeStart / 16
                    val blockOffset = (rangeStart % 16).toInt()

                    val adjustedIv  = incrementIv(ctrIv, blockStart)
                    val cipher      = Cipher.getInstance("AES/CTR/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE,
                                SecretKeySpec(aesKey, "AES"),
                                IvParameterSpec(adjustedIv))

                    // CDN Range request (aligned to 16-byte block boundary)
                    val cdnRangeStart = blockStart * 16
                    val cdnRangeEnd   = if (fileSize > 0) fileSize - 1 else ""
                    val cdnReq = Request.Builder()
                        .url(cdnUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) "  +
                                              "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                              "Chrome/147.0.0.0 Mobile Safari/537.36")
                        .header("Origin",  "https://mega.nz")
                        .header("Referer", "https://mega.nz/")
                        .header("Range",   "bytes=$cdnRangeStart-$cdnRangeEnd")
                        .build()

                    val cdnResp = httpClient.newCall(cdnReq).execute()
                    val body    = cdnResp.body ?: run {
                        sendError(output, 502); return
                    }

                    val contentLength = if (rangeEnd >= 0 && fileSize > 0)
                        rangeEnd - rangeStart + 1
                    else
                        body.contentLength()

                    // HTTP response headers
                    val status = if (rangeHeader != null) "206 Partial Content" else "200 OK"
                    val mime   = if (ext == "mkv") "video/x-matroska" else "video/mp4"
                    val sb = StringBuilder()
                    sb.append("HTTP/1.1 $status\r\n")
                    sb.append("Content-Type: $mime\r\n")
                    if (contentLength > 0) sb.append("Content-Length: $contentLength\r\n")
                    if (rangeHeader != null && fileSize > 0)
                        sb.append("Content-Range: bytes $rangeStart-$rangeEnd/$fileSize\r\n")
                    sb.append("Accept-Ranges: bytes\r\n")
                    sb.append("Connection: close\r\n\r\n")
                    output.write(sb.toString().toByteArray(Charsets.US_ASCII))

                    // Stream + decrypt
                    val encStream = body.byteStream()
                    val buf       = ByteArray(65536)
                    var toSkip    = blockOffset   // skip partial block prefix
                    var remaining = contentLength

                    while (remaining != 0L) {
                        val readLen = if (remaining > 0) minOf(buf.size.toLong(), remaining).toInt()
                                      else buf.size
                        val n = encStream.read(buf, 0, readLen)
                        if (n <= 0) break

                        val dec = cipher.update(buf, 0, n) ?: continue

                        val writeStart = toSkip
                        val writeLen   = dec.size - toSkip
                        if (writeLen > 0) {
                            output.write(dec, writeStart, writeLen)
                            if (remaining > 0) remaining -= writeLen
                        }
                        toSkip = 0
                    }

                    output.flush()
                    body.close()
                    cdnResp.close()
                }
            } catch (e: Exception) {
                println("⚠️ [MegaProxy] Client error: ${e.message}")
            }
        }

        private fun parseRange(header: String?, fileSize: Long): Pair<Long, Long> {
            if (header == null) return Pair(0L, if (fileSize > 0) fileSize - 1 else -1L)
            val m = Regex("""bytes=(\d*)-(\d*)""").find(header) ?: return Pair(0L, -1L)
            val s = m.groupValues[1].toLongOrNull() ?: 0L
            val e = m.groupValues[2].toLongOrNull() ?: (if (fileSize > 0) fileSize - 1 else -1L)
            return Pair(s, e)
        }

        /** Increment a 16-byte big-endian counter by `delta` blocks */
        private fun incrementIv(iv: ByteArray, delta: Long): ByteArray {
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

        private fun sendError(out: java.io.OutputStream, code: Int) {
            out.write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }
}
