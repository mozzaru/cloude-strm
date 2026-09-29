package com.Anichin

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

/**
 * Resolves Anichin's "Dailymotion [ADS]" relay (player.nunadrama.sbs/index.php?video=<id>).
 *
 * The relay page is a tiny HTML shell whose single iframe points at the real player
 * (currently https://geo.dailymotion.com/player/<hash>.html?video=<id>). This extractor is
 * deliberately self-contained — it parses the Dailymotion metadata API itself instead of
 * delegating to loadExtractor() for geo.dailymotion.com, because loadExtractor() resolves by
 * registration order and another installed plugin can shadow the core lookup.
 *
 * Verified live 2026-09-29 against the current episode pages (episodes 695 and 696).
 */
class Nunadrama : ExtractorApi() {
    override val name = "Nunadrama"
    override val mainUrl = "https://player.nunadrama.sbs"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Nunadrama", "getUrl: $url, referer: $referer")

        // The legacy anichin-player.web.id relay is resolved to a geo.dailymotion.com URL
        // before dispatch, so this extractor also accepts a direct Dailymotion URL.
        if (url.contains("dailymotion.com")) {
            val directId = extractDailymotionId(url)
            if (directId != null) {
                Log.d("Nunadrama", "Direct Dailymotion URL, id=$directId")
                emitLinks(directId, subtitleCallback, callback)
                return
            }
        }

        // Relay page: resolve its single iframe, then read the Dailymotion id from that URL
        // (the relay's own ?video= param is NOT trusted as the Dailymotion id).
        val html = app.get(url, referer = referer).text
        val iframeUrl = Jsoup.parse(html).selectFirst("iframe")?.attr("src")
            ?.takeIf { it.isNotBlank() }
        if (iframeUrl == null) {
            Log.w("Nunadrama", "No iframe found on relay page")
            return
        }
        val playerUrl = httpsify(iframeUrl)
        Log.d("Nunadrama", "Resolved inner player: $playerUrl")

        val videoId = extractDailymotionId(playerUrl)
        if (videoId == null) {
            // The relay currently always embeds Dailymotion; if that ever changes, fall back
            // to the generic extractor chain instead of silently dropping the server.
            Log.w("Nunadrama", "No Dailymotion id in $playerUrl, falling back to loadExtractor")
            loadExtractor(playerUrl, referer, subtitleCallback, callback)
            return
        }

        emitLinks(videoId, subtitleCallback, callback)
    }

    /** Pulls the Dailymotion video id out of geo.dailymotion.com/...?video=<id> (or /video/<id>). */
    private fun extractDailymotionId(url: String): String? =
        Regex("[?&]video=([^&]+)").find(url)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
            ?: Regex("/video/([^/?]+)").find(url)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }

    private suspend fun emitLinks(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = "https://www.dailymotion.com/embed/video/$videoId"
        val metadataUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
        val body = try {
            app.get(metadataUrl, referer = embedUrl).text
        } catch (e: Exception) {
            Log.w("Nunadrama", "Metadata request failed: ${e.message}")
            return
        }
        val meta = tryParseJson<DmMetadata>(body)
        if (meta == null) {
            Log.w("Nunadrama", "Metadata JSON parse failed")
            return
        }

        val streams = meta.qualities?.get("auto").orEmpty()
        val m3u8Urls = streams.mapNotNull { it.url }
            .filter { it.contains(".m3u8") }
            .distinct()
        if (m3u8Urls.isEmpty()) {
            Log.w("Nunadrama", "No m3u8 stream in metadata")
            return
        }

        for (streamUrl in m3u8Urls) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    // Verified: the CDN manifest accepts this referer (or none); the
                    // Dailymotion referer keeps playback working if they tighten checks.
                    this.referer = "https://www.dailymotion.com/"
                }
            )
        }
        Log.d("Nunadrama", "Emitted ${m3u8Urls.size} stream(s)")

        meta.subtitles?.data?.values?.forEach { subData ->
            subData.urls.forEach { subUrl ->
                subtitleCallback(newSubtitleFile(subData.label, subUrl))
            }
        }
    }

    @Serializable
    data class DmMetadata(
        @SerialName("qualities") val qualities: Map<String, List<DmStream>>? = null,
        @SerialName("subtitles") val subtitles: DmSubtitles? = null,
    )

    @Serializable
    data class DmStream(
        @SerialName("type") val type: String? = null,
        @SerialName("url") val url: String? = null,
    )

    @Serializable
    data class DmSubtitles(
        @SerialName("enable") val enable: Boolean = false,
        @SerialName("data") val data: Map<String, DmSubtitle>? = null,
    )

    @Serializable
    data class DmSubtitle(
        @SerialName("label") val label: String = "",
        @SerialName("urls") val urls: List<String> = emptyList(),
    )
}
