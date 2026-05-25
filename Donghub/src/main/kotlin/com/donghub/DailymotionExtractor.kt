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
    // Sama persis core — jangan ubah
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
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.i(TAG, "getUrl called → $url")

        val embedUrl = getEmbedUrl(url) ?: run {
            Log.e(TAG, "getEmbedUrl gagal untuk: $url")
            return
        }
        Log.d(TAG, "embedUrl → $embedUrl")

        val id = getVideoId(embedUrl) ?: run {
            Log.e(TAG, "getVideoId gagal untuk: $embedUrl")
            return
        }
        Log.i(TAG, "video id → $id")

        // Request metadata dengan headers lengkap
        // Desktop UA penting → dapat kualitas lebih tinggi dari CDN
        val metaUrl  = "$baseUrl/player/metadata/video/$id"
        Log.d(TAG, "fetching metadata: $metaUrl")

        val response = app.get(
            metaUrl,
            headers = mapOf(
                "Referer"         to embedUrl,
                "Origin"          to baseUrl,
                "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                     "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                     "Chrome/124.0.0.0 Safari/537.36",
                "Accept"          to "application/json, */*",
                "Accept-Language" to "en-US,en;q=0.9",
            ),
            referer = embedUrl
        )

        Log.i(TAG, "metadata HTTP ${response.code}")

        if (!response.isSuccessful) {
            Log.e(TAG, "metadata gagal HTTP ${response.code}: ${response.text.take(200)}")
            return
        }

        val meta = try {
            Gson().fromJson(response.text, MetaData::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
            Log.d(TAG, "raw response: ${response.text.take(300)}")
            return
        }

        val qualities = meta.qualities
        if (qualities.isNullOrEmpty()) {
            Log.e(TAG, "qualities kosong untuk id=$id")
            Log.d(TAG, "meta keys: ${
                try { Gson().fromJson(response.text, Map::class.java).keys } catch (_: Exception) { "parse failed" }
            }")
            return
        }

        Log.i(TAG, "quality keys tersedia: ${qualities.keys}")

        var added = 0

        // FIX UTAMA: tiap quality key → satu ExtractorLink dengan referer proper
        // TIDAK pakai generateM3u8 karena tidak bisa set referer di segment level
        for ((key, qValue) in qualityMap) {
            val streams = qualities[key] ?: continue
            for (quality in streams) {
                val streamUrl = quality.url ?: continue
                if (!streamUrl.contains(".m3u8")) {
                    Log.d(TAG, "skip non-m3u8 [$key]: $streamUrl")
                    continue
                }

                Log.i(TAG, "✅ stream ${key}p → ${streamUrl.take(80)}...")

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = "$name ${key}p",
                        url    = streamUrl,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.quality = qValue
                        this.referer = embedUrl
                        // Headers ini yang dibaca ExoPlayer saat request stream & segment
                        this.headers = mapOf(
                            "Referer"    to embedUrl,
                            "Origin"     to baseUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                           "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                           "Chrome/124.0.0.0 Safari/537.36",
                        )
                    }
                )
                added++
            }
        }

        // Fallback: "auto" jika tidak ada kualitas spesifik sama sekali
        if (added == 0) {
            Log.w(TAG, "tidak ada kualitas spesifik, fallback ke auto")
            qualities["auto"]?.forEach { quality ->
                val streamUrl = quality.url?.takeIf { it.contains(".m3u8") } ?: return@forEach
                Log.i(TAG, "✅ stream auto → ${streamUrl.take(80)}...")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = name,
                        url    = streamUrl,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = embedUrl
                        this.headers = mapOf(
                            "Referer"    to embedUrl,
                            "Origin"     to baseUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                           "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                           "Chrome/124.0.0.0 Safari/537.36",
                        )
                    }
                )
                added++
            }
        }

        Log.i(TAG, "selesai — total $added stream dikirim ke callback")

        // Subtitle
        meta.subtitles?.data?.forEach { (_, sub) ->
            sub.urls.forEach { subUrl ->
                subtitleCallback(newSubtitleFile(sub.label, subUrl))
            }
        }
    }

    // Sama persis core
    private fun getEmbedUrl(url: String): String? {
        if (url.contains("/embed/") || url.contains("/video/")) return url
        if (url.contains("geo.dailymotion.com")) {
            val videoId = url.substringAfter("video=")
            return "$baseUrl/embed/video/$videoId"
        }
        return null
    }

    // Sama persis core
    private fun getVideoId(url: String): String? {
        val path = URI(url).path
        val id   = path.substringAfter("/video/")
        return if (id.matches(videoIdRegex)) id else null
    }

    data class MetaData(
        val qualities : Map<String, List<Quality>>?,
        val subtitles : SubtitlesWrapper?
    )
    data class Quality(val type: String?, val url: String?)
    data class SubtitlesWrapper(val enable: Boolean, val data: Map<String, SubtitleData>?)
    data class SubtitleData(val label: String, val urls: List<String>)
}
