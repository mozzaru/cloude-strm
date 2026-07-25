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
        Log.i(TAG, "Using pageReferer: ${pageReferer.take(80)}")

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

    private fun headers(referer: String, isCDN: Boolean = false) = if (isCDN) mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
    ) else mapOf(
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

        // Use Dailymotion referer for CDN requests — CDN checks this header
        val cdnReferer = "https://www.dailymotion.com/"

        for (masterUrl in candidates) {
            if (tryParseAndEmit(masterUrl, cdnReferer, callback)) return
        }

        Log.w(TAG, "All CDN URLs failed, emitting last one anyway")
        emitLink(candidates.last(), cdnReferer, Qualities.Unknown.value, callback)
    }

    private suspend fun tryMetadataApi(videoId: String, referer: String): String? {
        return try {
            val url = "https://www.dailymotion.com/player/metadata/video/$videoId"
            Log.i(TAG, "Meta API request: $url")
            val resp = app.get(url, headers = headers(referer))
            val body = resp.text
            Log.i(TAG, "Meta API status: ${resp.code}, body(400): ${body.take(400)}")
            if (resp.code != 200) {
                Log.w(TAG, "Meta API non-200 status")
                return null
            }
            val meta = parseJson<MetadataResponse>(body)
            val autoList = meta.qualities?.auto
            Log.i(TAG, "Meta API: auto streams = ${autoList?.size}, types=${autoList?.map { it.type }}, urls=${autoList?.map { it.url?.take(80) }}")
            val urlM3u8 = autoList?.firstOrNull { it.url?.contains(".m3u8") == true }?.url
            if (urlM3u8 != null) {
                Log.i(TAG, "Meta API OK: $urlM3u8")
                return urlM3u8
            }
            val anyUrl = autoList?.firstOrNull { it.url != null }?.url
            if (anyUrl != null) {
                Log.w(TAG, "Meta API: no m3u8 URL, trying first non-null: $anyUrl")
                return anyUrl
            }
            Log.w(TAG, "Meta API: no auto URL at all")
            null
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
                Log.i(TAG, "Geo embed request: $geoUrl")
                val resp = app.get(geoUrl, headers = headers(ref))
                val html = resp.text
                Log.i(TAG, "Geo embed status: ${resp.code}, HTML(500): ${html.take(500)}")
                if (resp.code != 200) {
                    Log.w(TAG, "Geo embed non-200 status")
                    continue
                }

                val manifestUrl = Regex(""""manifestUrl"\s*:\s*"([^"]+)""").find(html)
                    ?.groupValues?.get(1)
                    ?.replace("\\/", "/")
                    ?.replace("\\u0026", "&")
                if (!manifestUrl.isNullOrBlank()) {
                    Log.i(TAG, "Geo embed manifestUrl OK: ${manifestUrl.take(120)}")
                    return manifestUrl
                }

                val directM3u8 = Regex("""https?://[^"'\s,]+?\.m3u8[^"'\s,]*""").find(html)?.value
                if (directM3u8 != null) {
                    Log.i(TAG, "Geo embed direct m3u8 OK: ${directM3u8.take(120)}")
                    return directM3u8
                }
                Log.w(TAG, "Geo embed: no manifestUrl or direct m3u8 found")
            } catch (e: Exception) {
                Log.w(TAG, "Geo embed failed: ${e.message}")
            }
        }
        return null
    }

    private suspend fun tryParseAndEmit(
        masterUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            Log.i(TAG, "CDN request: $masterUrl")
            Log.i(TAG, "CDN headers: User-Agent=${USER_AGENT.take(30)}..., Referer=$referer")
            val resp = app.get(masterUrl, headers = headers(referer, isCDN = true))
            val body = resp.text
            Log.i(TAG, "CDN response: status=${resp.code}, content-type=${resp.headers?.get("Content-Type")}, length=${body.length}")

            if (!body.startsWith("#EXTM3U")) {
                Log.w(TAG, "CDN returned invalid m3u8, prefix(300): ${body.take(300)}")
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