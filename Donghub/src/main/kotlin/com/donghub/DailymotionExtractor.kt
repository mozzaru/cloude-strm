package com.donghub

import com.google.gson.Gson
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

// Override GeoDailymotion juga agar konsisten
class DonghubGeoDailymotion : DonghubDailymotion() {
    override val name    = "GeoDailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

open class DonghubDailymotion : ExtractorApi() {
    override val name            = "Dailymotion"
    override val mainUrl         = "https://www.dailymotion.com"
    override val requiresReferer = false

    private val baseUrl      = "https://www.dailymotion.com"
    // Sama persis dengan core — jangan ubah regex ini
    private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

    private val qualityMap = mapOf(
        "2160" to Qualities.P2160.value,
        "1080" to Qualities.P1080.value,
        "720"  to Qualities.P720.value,
        "480"  to Qualities.P480.value,
        "380"  to Qualities.P360.value,
        "240"  to Qualities.P240.value,
        "144"  to Qualities.P144.value,
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = getEmbedUrl(url) ?: run {
            println("❌ [DonghubDailymotion] getEmbedUrl failed: $url"); return
        }
        val id = getVideoId(embedUrl) ?: run {
            println("❌ [DonghubDailymotion] getVideoId failed: $embedUrl"); return
        }

        println("▶ [DonghubDailymotion] id=$id")

        // FIX 1: tambah headers lengkap agar metadata API tidak 403
        val response = app.get(
            "$baseUrl/player/metadata/video/$id",
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

        if (!response.isSuccessful) {
            println("❌ [DonghubDailymotion] metadata HTTP ${response.code} for id=$id")
            return
        }

        val meta = try {
            Gson().fromJson(response.text, MetaData::class.java)
        } catch (e: Exception) {
            println("❌ [DonghubDailymotion] JSON parse: ${e.message}"); return
        }

        val qualities = meta.qualities
        if (qualities.isNullOrEmpty()) {
            println("❌ [DonghubDailymotion] empty qualities for id=$id"); return
        }

        println("▶ [DonghubDailymotion] quality keys: ${qualities.keys}")

        var added = 0

        // FIX 2: iterasi tiap kualitas individual untuk dapat 1080p/720p
        for ((key, qValue) in qualityMap) {
            qualities[key]?.forEach { quality ->
                val streamUrl = quality.url?.takeIf { it.contains(".m3u8") } ?: return@forEach
                // FIX 3: pass embedUrl sebagai referer — INI ROOT CAUSE ERROR 2004
                generateM3u8(name, streamUrl, embedUrl).forEach { link ->
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = "$name ${key}p",
                            url    = link.url,
                            type   = ExtractorLinkType.M3U8
                        ) {
                            this.quality = qValue
                            this.referer = embedUrl  // wajib ada referer untuk CDN
                            this.headers = mapOf(
                                "Referer" to embedUrl,
                                "Origin"  to baseUrl
                            )
                        }
                    )
                    added++
                }
            }
        }

        // Fallback: pakai "auto" jika tidak ada kualitas spesifik
        if (added == 0) {
            println("⚠ [DonghubDailymotion] fallback to auto")
            qualities["auto"]?.forEach { quality ->
                val streamUrl = quality.url?.takeIf { it.contains(".m3u8") } ?: return@forEach
                // FIX 3 berlaku di sini juga — referer wajib diisi
                generateM3u8(name, streamUrl, embedUrl).forEach(callback)
                added++
            }
        }

        println("✅ [DonghubDailymotion] added $added streams for id=$id")

        meta.subtitles?.data?.forEach { (_, sub) ->
            sub.urls.forEach { subUrl ->
                subtitleCallback(newSubtitleFile(sub.label, subUrl))
            }
        }
    }

    // Sama persis dengan core — tidak diubah
    private fun getEmbedUrl(url: String): String? {
        if (url.contains("/embed/") || url.contains("/video/")) return url
        if (url.contains("geo.dailymotion.com")) {
            val videoId = url.substringAfter("video=")
            return "$baseUrl/embed/video/$videoId"
        }
        return null
    }

    // Sama persis dengan core — tidak diubah
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
