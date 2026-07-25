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

class GeodailymotionFixed : DailymotionFixed() {
    override val name = "GeoDailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

open class DailymotionFixed : ExtractorApi() {
    override val name = "DailymotionFixed"
    override val mainUrl = "https://www.dailymotion.com"
    override val requiresReferer = false

    private val dmHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "https://www.dailymotion.com/",
        "Origin" to "https://www.dailymotion.com",
        "Accept" to "application/json, text/plain, */*",
    )

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
        Log.i(TAG, "Video ID: $videoId from: $url")

        val masterUrl = resolveMasterUrl(videoId)
        if (masterUrl != null) {
            Log.i(TAG, "Master URL: $masterUrl")
            parseMasterAndEmit(masterUrl, callback)
            return
        }

        Log.w(TAG, "All methods failed for video: $videoId")
    }

    private fun extractVideoId(url: String): String? {
        return Regex("[?&]video=([^&]+)").find(url)?.groupValues?.get(1)?.trim()
            ?: Regex("/video/([^/?]+)").find(url)?.groupValues?.get(1)?.trim()
            ?: Regex("/embed/([^/?]+)").find(url)?.groupValues?.get(1)?.trim()
            ?: Regex("""player/([^/]+)\.html""").find(url)?.groupValues?.get(1)?.trim()
            ?: url.substringAfterLast("/").substringBefore("?").substringBefore(".")
                .takeIf { it.matches(Regex("^[kx][a-zA-Z0-9]+$")) }
    }

    private suspend fun resolveMasterUrl(videoId: String): String? {
        // Method 1: Geo embed -> manifestUrl (bypasses 2004)
        val fromGeo = tryGeoEmbed(videoId)
        if (fromGeo != null) {
            Log.i(TAG, "Resolved via geo embed")
            return fromGeo
        }

        // Method 2: Metadata API -> find m3u8
        val fromMeta = tryMetadataApi(videoId)
        if (fromMeta != null) {
            Log.i(TAG, "Resolved via metadata API")
            return fromMeta
        }

        return null
    }

    private suspend fun tryGeoEmbed(videoId: String): String? {
        val urls = listOf(
            "https://geo.dailymotion.com/player.html?autoplay=0&mute=0&loop=0&controls=1&showinfo=1&video=$videoId",
            "https://geo.dailymotion.com/player/x1kcvu.html?video=$videoId",
        )
        for (geoUrl in urls) {
            try {
                val resp = app.get(geoUrl, headers = dmHeaders)
                val html = resp.text

                val manifestUrl = Regex(""""manifestUrl"\s*:\s*"([^"]+)""").find(html)
                    ?.groupValues?.get(1)
                    ?.replace("\\/", "/")
                    ?.replace("\\u0026", "&")

                if (!manifestUrl.isNullOrBlank()) {
                    return manifestUrl
                }

                val directM3u8 = Regex("""https?://[^"'\s,]+?\.m3u8[^"'\s,]*""").find(html)
                    ?.value
                if (directM3u8 != null) {
                    return directM3u8
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private suspend fun tryMetadataApi(videoId: String): String? {
        return try {
            val resp = app.get(
                "https://www.dailymotion.com/player/metadata/video/$videoId",
                headers = dmHeaders
            )
            val text = resp.text
            val unescaped = text.replace("\\/", "/").replace("\\u0026", "&")
            Regex("""https?://[^"'\s,]+?\.m3u8[^"'\s,]*""").find(unescaped)?.value
        } catch (e: Exception) {
            Log.w(TAG, "Metadata API failed: ${e.message}")
            null
        }
    }

    private suspend fun parseMasterAndEmit(masterUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            val resp = app.get(masterUrl, headers = dmHeaders)
            val body = resp.text

            if (!body.startsWith("#EXTM3U")) {
                emitLink(masterUrl, Qualities.Unknown.value, callback)
                return
            }

            val variantRegex = Regex("""#EXT-X-STREAM-INF(.*?)\n([^#\n]+)""", RegexOption.DOT_MATCHES_ALL)
            val matches = variantRegex.findAll(body).toList()

            if (matches.isEmpty()) {
                emitLink(masterUrl, Qualities.Unknown.value, callback)
                return
            }

            matches.forEach { match ->
                val params = match.groupValues[1]
                val variantUrl = match.groupValues[2].trim()

                val quality = parseQuality(params)

                val fullUrl = resolveRelativeUrl(masterUrl, variantUrl)
                emitLink(fullUrl, quality, callback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Master parse failed: ${e.message}")
            emitLink(masterUrl, Qualities.Unknown.value, callback)
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

    private fun resolveRelativeUrl(base: String, relative: String): String {
        return if (relative.startsWith("http")) relative
        else if (relative.startsWith("//")) "https:$relative"
        else {
            val basePath = base.substringBeforeLast("/")
            "$basePath/$relative"
        }
    }

    private suspend fun emitLink(url: String, quality: Int, callback: (ExtractorLink) -> Unit) {
        callback.invoke(newExtractorLink(
            source = name,
            name = name,
            url = url,
            type = ExtractorLinkType.M3U8,
        ) {
            this.quality = quality
            this.referer = "https://www.dailymotion.com/"
            this.headers = dmHeaders
        })
    }

    companion object {
        private const val TAG = "DailymotionFixed"
    }
}