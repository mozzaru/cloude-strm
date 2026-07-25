package com.donghub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class GeodailymotionFixed : DailymotionFixed() {
    override val name = "GeoDailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

open class DailymotionFixed : ExtractorApi() {
    override val name = "DailymotionFixed"
    override val mainUrl = "https://www.dailymotion.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val videoId = extractVideoId(url) ?: run {
            Log.w(TAG, "No video ID found in: $url")
            return
        }
        Log.i(TAG, "Video ID: $videoId referer: ${referer?.take(60)}")

        val pageReferer = referer?.ifBlank { null } ?: url

        getBestMasterUrl(videoId, pageReferer, callback)
    }

    private fun extractVideoId(url: String): String? {
        return Regex("[?&]video=([^&]+)").find(url)?.groupValues?.get(1)?.trim()
            ?: Regex("/video/([^/?]+)").find(url)?.groupValues?.get(1)?.trim()
            ?: Regex("/embed/([^/?]+)").find(url)?.groupValues?.get(1)?.trim()
            ?: Regex("""player/([^/]+)\.html""").find(url)?.groupValues?.get(1)?.trim()
            ?: url.substringAfterLast("/").substringBefore("?").substringBefore(".")
                .takeIf { it.matches(Regex("^[kx][a-zA-Z0-9]+$")) }
    }

    private fun headers(referer: String) = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Origin" to "https://www.dailymotion.com",
        "Accept" to "application/json, text/plain, */*",
        "Priority" to "u=1, i",
    )

    private suspend fun getBestMasterUrl(
        videoId: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val candidates = mutableListOf<String>()
        tryMetadataApi(videoId, referer)?.let { candidates.add(it) }
        tryGeoEmbed(videoId, referer)?.let { candidates.add(it) }

        if (candidates.isEmpty()) {
            Log.w(TAG, "All methods failed for video: $videoId")
            return
        }

        for (masterUrl in candidates) {
            if (tryParseAndEmit(masterUrl, referer, callback)) return
        }

        Log.w(TAG, "All CDN URLs failed, emitting last one anyway")
        emitLink(candidates.last(), referer, Qualities.Unknown.value, callback)
    }

    private suspend fun tryMetadataApi(videoId: String, referer: String): String? {
        return try {
            val resp = app.get(
                "https://www.dailymotion.com/player/metadata/video/$videoId",
                headers = headers(referer)
            )
            val meta = parseJson<MetadataResponse>(resp.text)
            val url = meta.qualities?.auto?.firstOrNull { it.url?.contains(".m3u8") == true }?.url
            if (url != null) Log.i(TAG, "Meta API OK") else Log.w(TAG, "Meta API: no auto URL")
            url
        } catch (e: Exception) {
            Log.w(TAG, "Meta API failed: ${e.message}")
            null
        }
    }

    private suspend fun tryGeoEmbed(videoId: String, referer: String): String? {
        val urls = listOf(
            "https://geo.dailymotion.com/player.html?autoplay=0&mute=0&loop=0&controls=1&showinfo=1&video=$videoId",
            "https://geo.dailymotion.com/player/x1kcvu.html?video=$videoId",
        )
        val ref = "https://www.dailymotion.com/"
        for (geoUrl in urls) {
            try {
                val resp = app.get(geoUrl, headers = headers(ref))
                val html = resp.text

                val manifestUrl = Regex(""""manifestUrl"\s*:\s*"([^"]+)""").find(html)
                    ?.groupValues?.get(1)
                    ?.replace("\\/", "/")
                    ?.replace("\\u0026", "&")
                if (!manifestUrl.isNullOrBlank()) {
                    Log.i(TAG, "Geo embed OK")
                    return manifestUrl
                }

                val directM3u8 = Regex("""https?://[^"'\s,]+?\.m3u8[^"'\s,]*""").find(html)?.value
                if (directM3u8 != null) {
                    Log.i(TAG, "Geo embed direct m3u8 OK")
                    return directM3u8
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private suspend fun tryParseAndEmit(
        masterUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val resp = app.get(masterUrl, headers = headers(referer))
            val body = resp.text

            if (!body.startsWith("#EXTM3U")) {
                Log.w(TAG, "CDN returned invalid m3u8")
                return false
            }

            val variantRegex = Regex("""#EXT-X-STREAM-INF(.*?)\n""", RegexOption.DOT_MATCHES_ALL)
            val bestQuality = variantRegex.findAll(body)
                .map { parseQuality(it.groupValues[1]) }
                .filter { it != Qualities.Unknown.value }
                .maxOrNull() ?: Qualities.Unknown.value

            emitLink(masterUrl, referer, bestQuality, callback)
            true
        } catch (e: Exception) {
            Log.w(TAG, "CDN failed: ${e.message}")
            false
        }
    }

    private fun parseQuality(params: String): Int {
        val name = Regex("""NAME\s*=\s*"(\d+)""").find(params)?.groupValues?.get(1)?.toIntOrNull()
        if (name != null) {
            return when {
                name >= 1080 -> Qualities.P1080.value
                name >= 720  -> Qualities.P720.value
                name >= 480  -> Qualities.P480.value
                name >= 360  -> Qualities.P360.value
                else         -> Qualities.Unknown.value
            }
        }

        val resolution = Regex("""RESOLUTION=\d+x(\d+)""").find(params)?.groupValues?.get(1)?.toIntOrNull()
        if (resolution != null) {
            return when {
                resolution >= 1080 -> Qualities.P1080.value
                resolution >= 720  -> Qualities.P720.value
                resolution >= 480  -> Qualities.P480.value
                resolution >= 360  -> Qualities.P360.value
                else               -> Qualities.Unknown.value
            }
        }

        val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(params)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return when {
            bandwidth >= 5_000_000 -> Qualities.P1080.value
            bandwidth >= 2_500_000 -> Qualities.P720.value
            bandwidth >= 1_000_000 -> Qualities.P480.value
            bandwidth >= 500_000   -> Qualities.P360.value
            else                   -> Qualities.Unknown.value
        }
    }

    private suspend fun emitLink(
        url: String,
        referer: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
    ) {
        callback.invoke(newExtractorLink(
            source = name,
            name = name,
            url = url,
            type = ExtractorLinkType.M3U8,
        ) {
            this.quality = quality
            this.referer = referer
            this.headers = headers(referer)
        })
    }

    @kotlinx.serialization.Serializable
    data class MetadataResponse(
        val qualities: QualitiesMap? = null
    )

    @kotlinx.serialization.Serializable
    data class QualitiesMap(
        val auto: List<Stream>? = null
    )

    @kotlinx.serialization.Serializable
    data class Stream(
        val type: String? = null,
        val url: String? = null
    )

    companion object {
        private const val TAG = "DailymotionFixed"
    }
}