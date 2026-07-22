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
    override val name = "Mega"
    override val mainUrl = "https://mega.nz"
    override val requiresReferer = false

    companion object {
        private const val TAG = "MegaNzExtractor"
        private const val MEGA_API = "https://g.api.mega.co.nz/cs"
        
        // Chunk 32KB - small enough for ExoPlayer to get data quickly
        private const val CHUNK_SIZE = 32 * 1024

        // Max CDN fetch per seek - 8MB enough for initial prefetch
        private const val MAX_CDN_FETCH = 8L * 1024 * 1024

        // Proxy yang tidak ada aktivitas sama sekali selama ini akan self-stop
        // (lihat MegaStreamProxy.acceptLoop) - jaring pengaman kalau extractor
        // lain gagal memanggil stopAll() saat user pindah server.
        private const val IDLE_TIMEOUT_MS = 30_000L

        private val httpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(true)
                .callTimeout(60, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        // 16 threads (naik dari 8) untuk beri ruang lebih kalau ada proxy lama
        // yang belum sempat dibersihkan - kombinasi dengan self-expiry di atas
        // supaya thread tidak pernah benar-benar habis/leak permanen.
        private val proxyExecutor by lazy {
            Executors.newFixedThreadPool(16) { r ->
                Thread(r, "mega-proxy-${System.nanoTime() % 100}").also {
                    it.isDaemon = true
                }
            }
        }

        // Active proxies list for cleanup
        private val activeProxies = mutableListOf<MegaStreamProxy>()

        /**
         * Stop semua proxy Mega yang aktif.
         * Dipanggil dari extractor lain (DTube, dll) sebelum mulai stream baru
         * supaya tidak ada konflik state yang menyebabkan DECODER_INIT_FAILED.
         */
        fun stopAll() {
            synchronized(activeProxies) {
                val n = activeProxies.size
                if (n > 0) Log.i(TAG, "stopAll(): menghentikan $n proxy aktif")
                activeProxies.toList().forEach { it.stop() }
                activeProxies.clear()
            }
        }

        fun megaB64Decode(s: String): ByteArray {
            val fixed = s.replace("-", "+").replace("_", "/")
            val pad   = (4 - fixed.length % 4) % 4
            return Base64.getDecoder().decode(fixed + "=".repeat(pad))
        }

        fun decodeFileKey(b64key: String): Pair<ByteArray, ByteArray> {
            val raw = megaB64Decode(b64key)
            val buf = ByteBuffer.wrap(raw)
            val k = IntArray(8) { buf.int }
            fun pack(vararg v: Int): ByteArray {
                val b = ByteBuffer.allocate(v.size * 4)
                v.forEach { b.putInt(it) }
                return b.array()
            }
            val aesKey = pack(k[0] xor k[4], k[1] xor k[5], k[2] xor k[6], k[3] xor k[7])
            val ctrIv = pack(k[4], k[5], 0, 0)
            return Pair(aesKey, ctrIv)
        }

        fun decryptAttrs(encB64: String, aesKey: ByteArray): Map<String, String>? = try {
            val enc = megaB64Decode(encB64)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                IvParameterSpec(ByteArray(16)))
            val dec = cipher.doFinal(enc)
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
            var carry = delta
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

        /**
         * Sama seperti getFileInfo, tapi blocking (pakai httpClient langsung)
         * karena dipanggil dari MegaStreamProxy yang berjalan di thread biasa
         * (proxyExecutor), bukan coroutine, jadi tidak bisa suspend.
         */
        fun fetchFileInfoBlocking(nodeId: String, maxAttempts: Int = 2): JsonObject? {
            var delayMs = 500L
            repeat(maxAttempts) { attempt ->
                try {
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
                    val text = resp.body?.string().orEmpty()
                    resp.close()
                    val arr = Json.parseToJsonElement(text).jsonArray
                    if (arr.isNotEmpty() && arr[0] is JsonObject) {
                        return arr[0].jsonObject
                    }
                    val code = if (arr.isNotEmpty()) arr[0].jsonPrimitive.intOrNull else null
                    Log.w(TAG, "fetchFileInfoBlocking: Mega error code $code (attempt ${attempt + 1})")
                    if (code != null && code != -3) return null
                } catch (e: Exception) {
                    Log.w(TAG, "fetchFileInfoBlocking failed (attempt ${attempt + 1}): ${e.message}")
                }
                if (attempt < maxAttempts - 1) {
                    Thread.sleep(delayMs)
                    delayMs *= 2
                }
            }
            return null
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
        val norm = normaliseUrl(url)
        val path = norm.removePrefix("$mainUrl/file/")
        val nodeId = path.substringBefore("#")
        val fileKey = path.substringAfter("#", "")
        
        Log.d(TAG, "Parse result - nodeId: ${nodeId.take(20)}..., fileKey: ${fileKey.take(20)}...")
        
        return if (nodeId.isBlank() || fileKey.isBlank()) {
            Log.e(TAG, "Cannot parse nodeId or fileKey")
            null
        } else {
            Pair(nodeId, fileKey)
        }
    }

    /**
     * Ambil satu kali info file dari Mega API.
     * Mega API bisa balas array berisi int (kode error, mis. -3 = temp
     * unavailable, -9 = not found, -11 = access denied) alih-alih object.
     * Return Pair(fileInfo, errorCode) - salah satu selalu null.
     */
    private suspend fun getFileInfoOnce(nodeId: String): Pair<JsonObject?, Int?> = try {
        val resp = app.post(
            "$MEGA_API?id=1",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Origin"       to "https://mega.nz",
                "Referer"      to "https://mega.nz/"
            ),
            requestBody = """[{"a":"g","g":1,"p":"$nodeId"}]"""
                .toRequestBody("application/json".toMediaType())
        )

        Log.d(TAG, "API response: ${resp.text.take(200)}")

        val arr = Json.parseToJsonElement(resp.text).jsonArray
        when {
            arr.isEmpty() -> {
                Log.w(TAG, "Empty API response")
                Pair(null, null)
            }
            arr[0] is JsonObject -> Pair(arr[0].jsonObject, null)
            else -> {
                val code = arr[0].jsonPrimitive.intOrNull
                Log.w(TAG, "Mega API returned error code: $code")
                Pair(null, code)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "getFileInfo error: ${e.message}")
        Pair(null, null)
    }

    /**
     * Retry dengan backoff. -3 (RequestFailedRetry / temp unavailable) hampir selalu
     * pulih dalam beberapa detik menurut dokumentasi Mega, jadi layak diretry.
     * Kode error lain (mis. -9 not found, -11 access denied) tidak diretry - langsung gagal.
     */
    private suspend fun getFileInfo(nodeId: String, maxAttempts: Int = 4): JsonObject? {
        var delayMs = 500L
        repeat(maxAttempts) { attempt ->
            Log.d(TAG, "Fetching file info for: $nodeId (attempt ${attempt + 1}/$maxAttempts)")
            val (info, errorCode) = getFileInfoOnce(nodeId)
            if (info != null) {
                Log.i(TAG, "File info retrieved successfully")
                return info
            }
            if (errorCode != null && errorCode != -3) {
                Log.e(TAG, "Non-retryable Mega error code $errorCode, giving up")
                return null
            }
            if (attempt < maxAttempts - 1) {
                Log.w(TAG, "Retrying getFileInfo in ${delayMs}ms...")
                kotlinx.coroutines.delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(4000L)
            }
        }
        Log.e(TAG, "getFileInfo failed after $maxAttempts attempts")
        return null
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

        // Hentikan proxy Mega yang mungkin masih berjalan dari sumber sebelumnya.
        // Dipanggil di sini supaya jika user pindah antar server Mega,
        // proxy lama langsung stop dan tidak ada konflik decoder.
        MegaNzExtractor.stopAll()

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
        // getFileInfo sendiri sudah retry dengan backoff untuk error transient (-3).
        // Fallback URL mentah (link mega.nz/file/... langsung) DIHAPUS karena tidak
        // pernah bisa diputar oleh ExoPlayer (file terenkripsi, bukan file media
        // langsung) - itu penyebab "loading lalu error" saat balik ke server Mega:
        // link yang diberikan ke player memang tidak pernah valid.
        val finfo = getFileInfo(nodeId)
        if (finfo == null) {
            Log.e(TAG, "getFileInfo gagal total setelah retry, tidak ada link yang bisa diberikan")
            return
        }

        val cdnUrl = finfo["g"]?.jsonPrimitive?.contentOrNull
        if (cdnUrl == null) {
            Log.e(TAG, "No CDN URL in response! keys=${finfo.keys.joinToString()}")
            return
        }
        
        val encAt = finfo["at"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val attrs = if (encAt.isNotBlank()) decryptAttrs(encAt, aesKey) else null
        val fileName = attrs?.get("n").orEmpty().ifBlank { "video.mp4" }
        val fileSize = finfo["s"]?.jsonPrimitive?.longOrNull ?: -1L
        val ext = fileName.substringAfterLast(".", "mp4").lowercase()
        val quality = guessQuality(fileName, fileSize)
        val label = qualityLabel(quality)

        Log.i(TAG, "File: '$fileName'  size=${fileSize / 1024 / 1024} MB  ext=$ext  quality=$label")
        Log.d(TAG, "CDN URL: ${cdnUrl.take(80)}...")

        Log.d(TAG, "Starting proxy server...")
        val proxy = MegaStreamProxy(
            nodeId = nodeId,
            cdnUrl = cdnUrl,
            aesKey = aesKey,
            ctrIv = ctrIv,
            fileSize = fileSize,
            ext = ext
        )
        val port = proxy.start()

        synchronized(activeProxies) {
            activeProxies.add(proxy)
            Log.i(TAG, "[:$port] terdaftar di activeProxies, total aktif=${activeProxies.size}")
        }

        val playUrl = "http://127.0.0.1:$port/video.$ext"
        Log.i(TAG, "Proxy started on port $port -> $playUrl")

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name",
                url = playUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
                this.referer = ""
            }
        )
        
        Log.i(TAG, "=== Mega extraction completed ===")
    }

    private inner class MegaStreamProxy(
        private val nodeId : String,
        @Volatile private var cdnUrl : String,
        private val aesKey : ByteArray,
        private val ctrIv : ByteArray,
        private val fileSize : Long,
        private val ext : String
    ) {
        private var serverSocket : ServerSocket? = null
        private val stopped = AtomicBoolean(false)

        // Port dipakai buat prefix log (mis. "[:41231]") supaya kalau ada
        // lebih dari satu proxy hidup bersamaan (indikasi leak), log tiap
        // instance bisa dibedakan di logcat.
        @Volatile private var port: Int = -1
        private fun tag() = "[:$port]"

        // Lacak koneksi/response yang sedang aktif supaya stop() bisa
        // memaksa tutup semua, tidak cuma serverSocket - ini penting karena
        // thread yang lagi blocking di client.read()/encStream.read() TIDAK
        // akan pernah cek flag `stopped`, jadi tidak akan pernah keluar
        // sampai socket-nya sendiri ditutup paksa. Sebelumnya thread begini
        // menggantung selamanya dan menghabiskan slot di proxyExecutor (cuma
        // 8 thread untuk SELURUH proxy Mega yang pernah dibuat) - itulah
        // kenapa server lain juga ikut error setelah beberapa kali gonta-ganti
        // dari/ke Mega: pool thread-nya sudah habis oleh proxy lama yang
        // tidak pernah benar-benar mati.
        private val clientSockets = java.util.Collections.synchronizedSet(mutableSetOf<java.net.Socket>())
        private val cdnResponses  = java.util.Collections.synchronizedSet(mutableSetOf<okhttp3.Response>())

        // Kalau tidak ada aktivitas sama sekali selama IDLE_TIMEOUT_MS (mis.
        // extractor lain lupa/gagal memanggil stopAll() saat user pindah
        // server), proxy ini bunuh diri sendiri supaya port & thread-nya
        // dilepas - jadi tidak bergantung 100% ke kerja sama extractor lain.
        @Volatile private var lastActivityMs = System.currentTimeMillis()

        fun start(): Int {
            var p = 0
            try {
                serverSocket = ServerSocket(0)
                p = serverSocket!!.localPort
                port = p
                Log.d(TAG, "${tag()} Server socket created on port $p")
                
                proxyExecutor.execute {
                    acceptLoop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start failed: ${e.message}")
            }
            return p
        }
        
        fun stop() {
            if (stopped.getAndSet(true)) {
                Log.d(TAG, "${tag()} stop() dipanggil lagi (sudah stopped sebelumnya), no-op")
                return
            }
            try {
                serverSocket?.close()
            } catch (_: Exception) {}
            val closedClients = synchronized(clientSockets) {
                val n = clientSockets.size
                clientSockets.forEach { try { it.close() } catch (_: Exception) {} }
                clientSockets.clear()
                n
            }
            val closedResponses = synchronized(cdnResponses) {
                val n = cdnResponses.size
                cdnResponses.forEach { try { it.close() } catch (_: Exception) {} }
                cdnResponses.clear()
                n
            }
            Log.i(TAG, "${tag()} stop(): $closedClients client socket & $closedResponses CDN response dipaksa tutup")
        }
        
        private fun acceptLoop() {
            try { serverSocket?.soTimeout = 200 } catch (_: Exception) {}
            Log.d(TAG, "${tag()} acceptLoop dimulai")
            while (!stopped.get()) {
                try {
                    val client = serverSocket!!.accept()
                    lastActivityMs = System.currentTimeMillis()

                    // ExoPlayer buka koneksi baru tiap kali seek/reposisi/retry -
                    // itu artinya request LAMA sudah tidak relevan. Kalau
                    // dibiarkan tetap jalan bersamaan, request lama & baru
                    // berebut bandwidth CDN yang sama (satu file yang sama!),
                    // saling bikin lambat, ExoPlayer mengira stall lalu buka
                    // koneksi baru LAGI - makin ramai berebut, spiral macet
                    // permanen (persis yang terlihat di log: total client
                    // aktif naik ke 3-5, semua dapat "Broken pipe" setelah
                    // cuma dapat data 1-2MB, tidak pernah maju). Jadi begitu
                    // ada client baru, putus paksa semua yang lama - cukup 1
                    // stream aktif per waktu, sesuai kebutuhan playback progresif.
                    val staleClients = synchronized(clientSockets) { clientSockets.toList() }
                    if (staleClients.isNotEmpty()) {
                        Log.i(TAG, "${tag()} client baru masuk, memutus ${staleClients.size} koneksi lama (single-flight)")
                        staleClients.forEach { try { it.close() } catch (_: Exception) {} }
                    }
                    val staleResponses = synchronized(cdnResponses) { cdnResponses.toList() }
                    staleResponses.forEach { try { it.close() } catch (_: Exception) {} }

                    clientSockets.add(client)
                    Log.d(TAG, "${tag()} client connect dari ${client.remoteSocketAddress}, total client aktif=${clientSockets.size}")
                    proxyExecutor.execute { handleClient(client) }
                } catch (e: java.net.SocketTimeoutException) {
                    // normal wakeup to re-check stopped flag
                    val idleFor = System.currentTimeMillis() - lastActivityMs
                    if (idleFor > IDLE_TIMEOUT_MS) {
                        Log.w(TAG, "${tag()} idle ${idleFor}ms > ${IDLE_TIMEOUT_MS}ms tanpa aktivitas, self-stop")
                        stop()
                        synchronized(activeProxies) {
                            activeProxies.remove(this@MegaStreamProxy)
                            Log.i(TAG, "${tag()} dihapus dari activeProxies (self-expired), sisa=${activeProxies.size}")
                        }
                        break
                    }
                } catch (e: Exception) {
                    if (!stopped.get()) Log.w(TAG, "${tag()} Accept failed: ${e.message}")
                    break
                }
            }
            Log.d(TAG, "${tag()} acceptLoop berhenti (stopped=${stopped.get()})")
        }
        
        private fun handleClient(client: java.net.Socket) {
            try {
                lastActivityMs = System.currentTimeMillis()
                val input = client.getInputStream().bufferedReader()
                val output = BufferedOutputStream(client.getOutputStream())
                
                val requestLine = input.readLine() ?: return
                Log.d(TAG, "${tag()} Request: $requestLine")
                
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

                Log.d(TAG, "${tag()} Range: $rangeStart-$rangeEnd  len=$contentLength")
                streamFromCdn(output, rangeHeader, rangeStart, rangeEnd, contentLength)
            } catch (e: Exception) {
                Log.w(TAG, "${tag()} handleClient error: ${e.message}")
            } finally {
                clientSockets.remove(client)
                try { client.close() } catch (_: Exception) {}
                Log.d(TAG, "${tag()} client disconnect, total client aktif=${clientSockets.size}")
            }
        }

        private fun streamFromCdn(
            output : BufferedOutputStream,
            rangeHeader : String?,
            rangeStart : Long,
            rangeEnd : Long,
            contentLength : Long
        ) {
            val blockStart = rangeStart / 16
            val blockOffset = (rangeStart % 16).toInt()
            val cdnFrom = blockStart * 16
            val adjustedIv = incrementIv(ctrIv, blockStart)

            // Jika rangeEnd eksplisit (ExoPlayer probe/seek), align ke block
            // boundary berikutnya saja — jangan tambah ekstra karena itu
            // menyebabkan CDN kirim data jauh melebihi yang dibutuhkan dan
            // ExoPlayer sering disconnect → DECODER_INIT_FAILED.
            //
            // Jika open-ended (rangeEnd <= 0, ini request normal playback),
            // dulu dibatasi MAX_CDN_FETCH (8MB) lalu putus dan reconnect lagi
            // ke CDN tiap ~8MB - ini penyebab utama "kadang buffering" karena
            // tiap reconnect = TLS handshake + request baru ke Mega. Sekarang
            // stream sampai akhir file dalam SATU koneksi selama client masih
            // terhubung; koneksi otomatis ditutup saat client disconnect/seek
            // (lihat finally block di bawah).
            val cdnTo = if (rangeEnd > 0) {
                val aligned = ((rangeEnd / 16) + 1) * 16 - 1
                if (fileSize > 0) aligned.coerceAtMost(fileSize - 1) else aligned
            } else {
                if (fileSize > 0) fileSize - 1 else cdnFrom + MAX_CDN_FETCH - 1
            }
            val cdnRangeHdr = "bytes=$cdnFrom-$cdnTo"

            Log.d(TAG, "${tag()} CDN range -> $cdnRangeHdr  (blockOffset=$blockOffset)")

            // Kode HTTP yang layak diretry / URL CDN-nya perlu di-refresh dari Mega API:
            // 403 = signed URL kadaluarsa, 404 = kadang muncul saat storage node reshuffle,
            // 429/500/502/503/504 = rate-limit / gangguan sementara di sisi CDN.
            val refreshableCodes = setOf(403, 404, 429, 500, 502, 503, 504)
            val maxAttempts = 3
            var attemptCdnUrl = cdnUrl
            var cdnResp: okhttp3.Response? = null

            for (attempt in 1..maxAttempts) {
                val req = Request.Builder()
                    .url(attemptCdnUrl)
                    .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; K) " +
                        "AppleWebKit/537.36 Chrome/149.0.0.0 Mobile Safari/537.36")
                    .header("Origin",  "https://mega.nz")
                    .header("Referer", "https://mega.nz/")
                    .header("Range",   cdnRangeHdr)
                    .build()

                val resp = try {
                    httpClient.newCall(req).execute()
                } catch (e: Exception) {
                    Log.w(TAG, "${tag()} CDN attempt $attempt/$maxAttempts connect failed: ${e.message}")
                    null
                }

                if (resp != null && resp.code in listOf(200, 206)) {
                    cdnResp = resp
                    break
                }

                if (resp != null) {
                    Log.w(TAG, "${tag()} CDN attempt $attempt/$maxAttempts returned ${resp.code}")
                    resp.close()
                }

                if (attempt == maxAttempts) break

                // Kalau errornya jenis yang biasanya berarti signed URL sudah tidak
                // valid, ambil URL CDN baru dari Mega API sebelum retry berikutnya.
                if (resp == null || resp.code in refreshableCodes) {
                    val freshInfo = fetchFileInfoBlocking(nodeId, maxAttempts = 2)
                    val freshCdnUrl = freshInfo?.get("g")?.jsonPrimitive?.contentOrNull
                    if (freshCdnUrl != null) {
                        Log.i(TAG, "${tag()} Got fresh CDN URL for attempt ${attempt + 1}")
                        attemptCdnUrl = freshCdnUrl
                        cdnUrl = freshCdnUrl  // update outer state untuk request berikutnya
                    }
                }
                Thread.sleep(500L * attempt)
            }

            if (cdnResp == null) {
                Log.e(TAG, "${tag()} CDN gagal setelah $maxAttempts percobaan, giving up")
                sendError(output, 502)
                return
            }
            cdnResponses.add(cdnResp)

            val body = cdnResp.body ?: run {
                Log.e(TAG, "${tag()} CDN body null")
                sendError(output, 502)
                cdnResponses.remove(cdnResp)
                cdnResp.close()
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
            val buf = ByteArray(CHUNK_SIZE)
            var toSkip = blockOffset
            var remaining = contentLength
            var totalSent = 0L

            var bytesSinceFlush = 0L
            val FLUSH_EVERY = 256 * 1024L

            try {
                while (remaining != 0L && !stopped.get()) {
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
                    val toWrite = if (remaining > 0)
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
                        lastActivityMs = System.currentTimeMillis()
                    }
                }

                output.flush()
                Log.d(TAG, "${tag()} Stream done - sent ${totalSent / 1024} KB")

            } catch (e: java.io.IOException) {
                Log.d(TAG, "${tag()} Client disconnected after ${totalSent / 1024} KB: ${e.message}")
            } finally {
                body.close()
                cdnResp.close()
                cdnResponses.remove(cdnResp)
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
            val mime = when (ext) {
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                else -> "video/mp4"
            }
            val status = if (statusCode == 206) "206 Partial Content" else "200 OK"
            val sb = StringBuilder()
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
