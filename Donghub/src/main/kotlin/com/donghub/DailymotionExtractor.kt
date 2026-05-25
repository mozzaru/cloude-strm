package com.donghub

import com.lagradost.api.Log
import com.google.gson.Gson
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

open class DonghubDailymotion : ExtractorApi() {
    override val name            = "Dailymotion"
    override val mainUrl         = "https://www.dailymotion.com"
    override val requiresReferer = false

    private val baseUrl      = "https://www.dailymotion.com"
    private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

    companion object {
        private const val TAG = "DonghubDailymotion"

        private val qualityMap = linkedMapOf(
            "2160" to Qualities.P2160.value,
            "1080" to Qualities.P1080.value,
            "720"  to Qualities.P720.value,
            "480"  to Qualities.P480.value,
            "380"  to Qualities.P360.value,
            "240"  to Qualities.P240.value,
            "144"  to Qualities.P144.value,
        )

        private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.i(TAG, "getUrl → $url")

        val embedUrl = getEmbedUrl(url) ?: run {
            Log.e(TAG, "getEmbedUrl gagal: $url"); return
        }
        val id = getVideoId(embedUrl) ?: run {
            Log.e(TAG, "getVideoId gagal: $embedUrl"); return
        }
        Log.i(TAG, "video id → $id | embedUrl → $embedUrl")

        val commonHeaders = mapOf(
            "Referer"    to embedUrl,
            "Origin"     to baseUrl,
            "User-Agent" to DESKTOP_UA,
            "Accept"     to "application/json, */*",
        )

        val metaUrl  = "$baseUrl/player/metadata/video/$id"
        val response = app.get(metaUrl, headers = commonHeaders, referer = embedUrl)
        Log.i(TAG, "metadata HTTP ${response.code}")

        if (!response.isSuccessful) {
            Log.e(TAG, "metadata gagal ${response.code}"); return
        }

        val meta = try {
            Gson().fromJson(response.text, MetaData::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}"); return
        }

        val qualities = meta.qualities
        if (qualities.isNullOrEmpty()) {
            Log.e(TAG, "qualities kosong"); return
        }

        Log.i(TAG, "quality keys: ${qualities.keys}")

        var added = 0

        // ── Strategy 1: Quality spesifik (MP4 prioritas → audio muxed) ──────
        for ((key, qValue) in qualityMap) {
            val streams = qualities[key] ?: continue

            val mp4Url = streams.firstOrNull { q ->
                q.type?.contains("mp4", ignoreCase = true) == true ||
                q.url?.contains(".mp4") == true
            }?.url

            val m3u8Url = streams.firstOrNull { q ->
                q.url?.contains(".m3u8") == true
            }?.url

            val streamUrl = mp4Url ?: m3u8Url ?: continue
            val isM3u8    = streamUrl.contains(".m3u8")

            Log.i(TAG, "✅ ${key}p [${if (mp4Url != null) "MP4" else "M3U8"}] → ${streamUrl.take(80)}...")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name   = "$name ${key}p",
                    url    = streamUrl,
                    type   = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = qValue
                    this.referer = embedUrl
                    this.headers = mapOf(
                        "Referer"    to embedUrl,
                        "Origin"     to baseUrl,
                        "User-Agent" to DESKTOP_UA,
                    )
                }
            )
            added++
        }

        // ── Strategy 2: Hanya "auto" → kirim master m3u8 langsung ───────────
        // ExoPlayer handle: audio group + quality track + adaptive buffering
        if (added == 0) {
            Log.w(TAG, "quality spesifik tidak ada, pakai master m3u8 langsung")

            val masterUrl = qualities["auto"]
                ?.firstOrNull { it.url?.contains(".m3u8") == true }
                ?.url ?: run {
                    Log.e(TAG, "tidak ada URL yang bisa dipakai"); return
                }

            val maxQuality = detectMaxQuality(masterUrl, commonHeaders, embedUrl)

            Log.i(TAG, "✅ master stream → ${masterUrl.take(80)}...")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name   = name,
                    url    = masterUrl,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.quality = maxQuality
                    this.referer = embedUrl
                    this.headers = mapOf(
                        "Referer"    to embedUrl,
                        "Origin"     to baseUrl,
                        "User-Agent" to DESKTOP_UA,
                    )
                }
            )
            added = 1
        }

        Log.i(TAG, "selesai — $added stream dikirim")

        // Subtitle
        meta.subtitles?.data?.forEach { (_, sub) ->
            sub.urls.forEach { subUrl ->
                subtitleCallback(newSubtitleFile(sub.label, subUrl))
            }
        }
    }

    private suspend fun detectMaxQuality(
        masterUrl : String,
        headers   : Map<String, String>,
        referer   : String
    ): Int {
        return try {
            val text      = app.get(masterUrl, headers = headers, referer = referer).text
            val maxHeight = Regex("""RESOLUTION=\d+x(\d+)""")
                .findAll(text)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .maxOrNull() ?: return Qualities.Unknown.value

            Log.d(TAG, "max resolution: ${maxHeight}p")

            qualityMap.entries
                .minByOrNull { (k, _) -> kotlin.math.abs((k.toIntOrNull() ?: 0) - maxHeight) }
                ?.value ?: Qualities.Unknown.value
        } catch (e: Exception) {
            Log.w(TAG, "detectMaxQuality gagal: ${e.message}")
            Qualities.Unknown.value
        }
    }

    /**
     * Support semua format URL Dailymotion:
     * 1. https://www.dailymotion.com/embed/video/ID
     * 2. https://www.dailymotion.com/video/ID
     * 3. https://www.dailymotion.com/video/ID?param=value
     * 4. https://geo.dailymotion.com/player/...?video=ID
     * 5. https://cdndirector.dailymotion.com/cdn/manifest/video/ID.m3u8?sec=...
     * 6. https://dai.ly/ID  (short URL)
     * 7. ID mentah: xab0dty / k4I9E2HCZ2YnSQGaHcy
     */
    private fun getEmbedUrl(url: String): String? {
        val cleaned = url.trim()

        // ── Sudah embed URL ──────────────────────────────────────────────────
        if (cleaned.contains("/embed/video/")) return cleaned

        // ── URL video biasa (dengan atau tanpa query param) ──────────────────
        if (cleaned.contains("dailymotion.com/video/")) {
            val id = cleaned
                .substringAfter("/video/")
                .substringBefore("?")
                .substringBefore("/")
                .trim()
            return if (id.matches(videoIdRegex)) "$baseUrl/embed/video/$id" else null
        }

        // ── cdndirector.dailymotion.com ──────────────────────────────────────
        // https://cdndirector.dailymotion.com/cdn/manifest/video/xab0dty.m3u8?sec=...
        if (cleaned.contains("cdndirector.dailymotion.com")) {
            val id = cleaned
                .substringAfter("/video/")
                .substringBefore(".m3u8")
                .substringBefore("?")
                .substringBefore("/")
                .trim()
            return if (id.matches(videoIdRegex)) "$baseUrl/embed/video/$id" else null
        }

        // ── geo.dailymotion.com ──────────────────────────────────────────────
        if (cleaned.contains("geo.dailymotion.com")) {
            val id = cleaned
                .substringAfter("video=")
                .substringBefore("&")
                .substringBefore("/")
                .trim()
            return if (id.matches(videoIdRegex)) "$baseUrl/embed/video/$id" else null
        }

        // ── dai.ly short URL ─────────────────────────────────────────────────
        if (cleaned.contains("dai.ly/")) {
            val id = cleaned
                .substringAfter("dai.ly/")
                .substringBefore("?")
                .substringBefore("/")
                .trim()
            return if (id.matches(videoIdRegex)) "$baseUrl/embed/video/$id" else null
        }

        // ── ID mentah (xab0dty / k4I9E2HCZ2YnSQGaHcy) ──────────────────────
        val rawId = cleaned.substringBefore("?").substringBefore("/").trim()
        if (rawId.matches(videoIdRegex)) {
            return "$baseUrl/embed/video/$rawId"
        }

        return null
    }

    private fun getVideoId(url: String): String? {
        return try {
            val path = URI(url).path
            val id   = path
                .substringAfter("/video/")
                .substringBefore("?")
                .substringBefore("/")
                .trim()
            if (id.matches(videoIdRegex)) id else null
        } catch (e: Exception) {
            Log.e(TAG, "getVideoId error: ${e.message}")
            null
        }
    }

    data class MetaData(
        val qualities : Map<String, List<Quality>>?,
        val subtitles : SubtitlesWrapper?
    )
    data class Quality(val type: String?, val url: String?)
    data class SubtitlesWrapper(val enable: Boolean, val data: Map<String, SubtitleData>?)
    data class SubtitleData(val label: String, val urls: List<String>)
}
