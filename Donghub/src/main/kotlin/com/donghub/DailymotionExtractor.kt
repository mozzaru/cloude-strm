package com.donghub

import com.google.gson.Gson
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI

class DonghubGeodailymotion : DonghubDailymotion() {
    override val name = "GeoDailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

open class DonghubDailymotion : ExtractorApi() {
    override val mainUrl = "https://www.dailymotion.com"
    override val name = "Dailymotion"
    override val requiresReferer = false
    private val baseUrl = "https://www.dailymotion.com"

    private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

    // Maps Dailymotion quality keys → CloudStream quality int
    private val qualityMap = mapOf(
        "1080" to Qualities.P1080,
        "720"  to Qualities.P720,
        "480"  to Qualities.P480,
        "360"  to Qualities.P360,
        "240"  to Qualities.P240,
        "auto" to Qualities.Unknown
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = getEmbedUrl(url) ?: return
        val id = getVideoId(embedUrl) ?: return
        val metaDataUrl = "$baseUrl/player/metadata/video/$id"

        val response = app.get(metaDataUrl, referer = embedUrl).text
        val meta = Gson().fromJson(response, MetaData::class.java)

        // ── Video streams ────────────────────────────────────────────────
        // Prefer specific qualities first so players show 1080/720/etc.
        // "auto" is included last as a fallback adaptive stream.
        val preferredOrder = listOf("1080", "720", "480", "360", "240", "auto")

        val sortedQualities = meta.qualities
            ?.entries
            ?.sortedBy { (key, _) ->
                preferredOrder.indexOf(key).takeIf { it >= 0 } ?: preferredOrder.size
            } ?: emptyList()

        sortedQualities.forEach { (qualityKey, qualityList) ->
            qualityList.forEach { quality ->
                val videoUrl = quality.url ?: return@forEach
                if (!videoUrl.contains(".m3u8")) return@forEach

                val qualityValue = qualityMap[qualityKey]?.value ?: Qualities.Unknown.value
                val labelSuffix = if (qualityKey == "auto") "Auto" else "${qualityKey}p"

                // Use ExtractorLink directly with isM3u8 = true so ExoPlayer
                // handles muxed audio tracks natively — fixes 1080p no-audio
                // and eliminates the lag from generateM3u8 pre-processing.
                callback(
                    ExtractorLink(
                        source   = this.name,
                        name     = "${this.name} $labelSuffix",
                        url      = videoUrl,
                        referer  = embedUrl,
                        quality  = qualityValue,
                        type     = ExtractorLinkType.M3U8
                    )
                )
            }
        }

        // ── Subtitles ────────────────────────────────────────────────────
        meta.subtitles?.data?.forEach { (_, subData) ->
            subData.urls.forEach { subUrl ->
                subtitleCallback(newSubtitleFile(subData.label, subUrl))
            }
        }
    }

    // ── URL helpers ──────────────────────────────────────────────────────

    private fun getEmbedUrl(url: String): String? {
        if (url.contains("/embed/") || url.contains("/video/")) return url
        if (url.contains("geo.dailymotion.com")) {
            val videoId = url.substringAfter("video=")
            return "$baseUrl/embed/video/$videoId"
        }
        return null
    }

    private fun getVideoId(url: String): String? {
        val path = URI(url).path
        val id = path.substringAfterLast("/video/")
        return if (id.matches(videoIdRegex)) id else null
    }

    // ── Data classes ─────────────────────────────────────────────────────

    data class MetaData(
        val qualities: Map<String, List<Quality>>?,
        val subtitles: SubtitlesWrapper?
    )

    data class Quality(
        val type: String?,
        val url: String?
    )

    data class SubtitlesWrapper(
        val enable: Boolean,
        val data: Map<String, SubtitleData>?
    )

    data class SubtitleData(
        val label: String,
        val urls: List<String>
    )
}
