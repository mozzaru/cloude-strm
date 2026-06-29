package com.donghub

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * CustomDailymotion + CustomGeoDailymotion
 * =========================================
 * Override CS3 core Dailymotion.kt yang punya 2 bug:
 *
 * BUG #1 – qualities hanya key "auto"
 *   Core: meta.qualities?.get("auto")?.forEach { ... }
 *   Kalau DM tidak return key "auto" → 0 stream di-callback
 *   → ExoPlayer ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (2001)
 *   FIX: iterate semua quality key dengan priority order.
 *
 * BUG #2 – geo referer di-convert ke www embed
 *   Core: getEmbedUrl() selalu return www.dailymotion.com/embed/video/ID
 *   CDN DM kadang butuh geo referer asli agar token metadata valid.
 *   FIX: pertahankan geo URL asli sebagai referer.
 */

// ── Data classes ──────────────────────────────────────────────

private data class DmMetaData(
    val qualities: Map<String, List<DmQuality>>?,
    val subtitles: DmSubtitlesWrapper?
)

private data class DmQuality(
    val type: String?,
    val url: String?
)

private data class DmSubtitlesWrapper(
    val enable: Boolean?,
    val data: Map<String, DmSubtitleData>?
)

private data class DmSubtitleData(
    val label: String,
    val urls: List<String>
)

// ── geo.dailymotion.com handler ───────────────────────────────

class CustomGeoDailymotion : CustomDailymotion() {
    override val name    = "Dailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

// ── www.dailymotion.com handler ───────────────────────────────

open class CustomDailymotion : ExtractorApi() {
    override val name            = "Dailymotion"
    override val mainUrl         = "https://www.dailymotion.com"
    override val requiresReferer = false

    private val baseUrl         = "https://www.dailymotion.com"
    private val videoIdRegex    = Regex("^[kx][a-zA-Z0-9]+$")
    private val QUALITY_PRIORITY = listOf("auto", "1080", "720", "480", "380", "240", "144")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val (videoId, correctReferer) = parseVideoInfo(url) ?: return

        val metaDataUrl = "$baseUrl/player/metadata/video/$videoId"

        val response = app.get(
            metaDataUrl,
            referer  = correctReferer,
            headers  = mapOf("Origin" to baseUrl, "Accept" to "*/*")
        ).text

        val meta = try {
            parseJson<DmMetaData>(response)
        } catch (e: Exception) {
            return
        }

        val qualities = meta.qualities ?: return

        // FIX #1: iterate SEMUA quality key, bukan hanya "auto"
        val seen    = mutableSetOf<String>()
        val allKeys = QUALITY_PRIORITY + qualities.keys.filter { it !in QUALITY_PRIORITY }

        for (key in allKeys) {
            for (entry in qualities[key] ?: continue) {
                val videoUrl = entry.url?.takeIf { it.contains(".m3u8") } ?: continue
                if (!seen.add(videoUrl)) continue

                val qualityInt = when (key) {
                    "1080" -> Qualities.P1080.value
                    "720"  -> Qualities.P720.value
                    "480"  -> Qualities.P480.value
                    "380"  -> Qualities.P360.value
                    "240"  -> Qualities.P240.value
                    "144"  -> Qualities.P144.value
                    else   -> Qualities.Unknown.value  // "auto" dan unknown
                }

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = name,
                        url    = videoUrl,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.quality = qualityInt
                        this.referer = correctReferer
                    }
                )
            }
        }

        // Subtitles
        meta.subtitles?.data?.forEach { (_, subData) ->
            subData.urls.forEach { subUrl ->
                subtitleCallback(newSubtitleFile(subData.label, subUrl))
            }
        }
    }

    // ── URL parsing ───────────────────────────────────────────

    private fun parseVideoInfo(url: String): Pair<String, String>? {
        val s = url.trim()

        // Raw video ID: kXXX atau xXXX
        if (s.matches(videoIdRegex)) {
            return Pair(s, "$baseUrl/embed/video/$s")
        }

        // geo.dailymotion.com/player/xxx.html?video=ID
        if ("geo.dailymotion.com" in s) {
            val vid = Regex("""[?&]video=([kx][a-zA-Z0-9]+)""").find(s)
                ?.groupValues?.get(1)
                ?: s.substringAfterLast("/").replace(".html", "")
                    .takeIf { it.matches(videoIdRegex) }
                ?: return null
            // FIX #2: pakai geo URL asli sebagai referer
            return Pair(vid, s)
        }

        // www.dailymotion.com/embed/video/ID atau /video/ID
        if ("dailymotion.com" in s) {
            val vid = Regex("""/video/([kx][a-zA-Z0-9]+)""").find(s)
                ?.groupValues?.get(1)
                ?: return null
            return Pair(vid, "$baseUrl/embed/video/$vid")
        }

        return null
    }
}
