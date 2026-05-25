package com.donghub

import com.google.gson.Gson
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class DonghubDailymotion : ExtractorApi() {
    override val name            = "Dailymotion"
    override val mainUrl         = "https://www.dailymotion.com"
    override val requiresReferer = false

    private val baseUrl      = "https://www.dailymotion.com"
    private val videoIdRegex = Regex("""/video/([kx][a-zA-Z0-9]{5,})""")

    // Map key API → Qualities value
    private val qualityMap = mapOf(
        "2160" to Qualities.P2160.value,
        "1080" to Qualities.P1080.value,
        "720"  to Qualities.P720.value,
        "480"  to Qualities.P480.value,
        "380"  to Qualities.P360.value,
        "240"  to Qualities.P240.value,
        "144"  to Qualities.P144.value,
        "auto" to Qualities.Unknown.value   // fallback jika semua gagal
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = normalizeEmbedUrl(url) ?: run {
            println("❌ [DonghubDailymotion] cannot normalize url=$url"); return
        }
        val id = extractVideoId(embedUrl) ?: run {
            println("❌ [DonghubDailymotion] cannot extract id from url=$embedUrl"); return
        }

        println("▶ [DonghubDailymotion] id=$id")

        val response = app.get(
            "$baseUrl/player/metadata/video/$id",
            headers = mapOf(
                "Referer"         to embedUrl,
                "Origin"          to baseUrl,
                "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                     "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                     "Chrome/124.0.0.0 Safari/537.36",  // desktop UA → dapat kualitas lebih tinggi
                "Accept"          to "application/json, */*",
                "Accept-Language" to "en-US,en;q=0.9"
            ),
            referer = embedUrl
        )

        if (!response.isSuccessful) {
            println("❌ [DonghubDailymotion] metadata HTTP ${response.code} for id=$id")
            return
        }

        val meta = try {
            Gson().fromJson(response.text, MetaData::class.java)
        } catch (e: Exception) {
            println("❌ [DonghubDailymotion] JSON parse error: ${e.message}"); return
        }

        val qualities = meta.qualities
        if (qualities.isNullOrEmpty()) {
            println("❌ [DonghubDailymotion] no qualities in metadata for id=$id")
            return
        }

        println("▶ [DonghubDailymotion] available quality keys: ${qualities.keys}")

        var totalAdded = 0

        // FIX UTAMA: iterasi tiap key kualitas INDIVIDUAL — skip "auto"
        // karena "auto" = adaptive stream yang sering resolve ke kualitas rendah
        for ((key, qualityValue) in qualityMap) {
            if (key == "auto") continue  // skip auto, tangani terakhir sebagai fallback

            val streams = qualities[key] ?: continue
            streams.forEach { quality ->
                val streamUrl = quality.url ?: return@forEach
                if (!streamUrl.contains(".m3u8")) return@forEach

                println("✅ [DonghubDailymotion] adding ${key}p stream")

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = "$name ${key}p",
                        url    = streamUrl,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.quality = qualityValue
                        this.referer = embedUrl
                    }
                )
                totalAdded++
            }
        }

        // Fallback: pakai "auto" hanya jika tidak ada kualitas spesifik sama sekali
        if (totalAdded == 0) {
            println("⚠ [DonghubDailymotion] no specific qualities found, fallback to auto")
            qualities["auto"]?.forEach { quality ->
                val streamUrl = quality.url ?: return@forEach
                if (!streamUrl.contains(".m3u8")) return@forEach
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = name,
                        url    = streamUrl,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = embedUrl
                    }
                )
            }
        }

        // Subtitle
        meta.subtitles?.data?.forEach { (_, sub) ->
            sub.urls.forEach { subUrl ->
                subtitleCallback(newSubtitleFile(sub.label, subUrl))
            }
        }
    }

    private fun normalizeEmbedUrl(url: String): String? {
        if (url.contains("/embed/video/") || url.contains("/video/")) return url
        if (url.contains("geo.dailymotion.com")) {
            val id = try {
                java.net.URI(url).query
                    ?.split("&")
                    ?.firstOrNull { it.startsWith("video=") }
                    ?.substringAfter("video=")
            } catch (_: Exception) { null }
                ?: url.substringAfter("video=").substringBefore("&")
            if (!id.isNullOrBlank()) return "$baseUrl/embed/video/$id"
        }
        return null
    }

    private fun extractVideoId(url: String): String? =
        videoIdRegex.find(url)?.groupValues?.get(1)

    data class MetaData(
        val qualities : Map<String, List<Quality>>?,
        val subtitles : SubtitlesWrapper?
    )
    data class Quality(val type: String?, val url: String?)
    data class SubtitlesWrapper(val enable: Boolean, val data: Map<String, SubtitleData>?)
    data class SubtitleData(val label: String, val urls: List<String>)
}
