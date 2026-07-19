package com.Anichin

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class AnichinPlayer : ExtractorApi() {
    override val name = "AnichinPlayer"
    override val mainUrl = "https://anichin-player.web.id"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("AnichinPlayer", "getUrl: $url, referer: $referer")

        val response = app.get(url, referer = referer)
        val html = response.text
        Log.d("AnichinPlayer", "Page size: ${html.length}, finalUrl: ${response.url}")

        val doc = Jsoup.parse(html)
        val allIframes = doc.select("iframe")
        Log.d("AnichinPlayer", "Total iframes: ${allIframes.size}")
        allIframes.forEachIndexed { i, iframe ->
            Log.d("AnichinPlayer", "  iframe[$i]: src=${iframe.attr("src")}")
        }

        Log.d("AnichinPlayer", "Checking for Dailymotion embed...")
        val dmMatch = Regex("""src="(https://geo\.dailymotion\.com[^"]*)""").find(html)
        if (dmMatch != null) {
            val dmUrl = dmMatch.groupValues[1].replace("&amp;", "&")
            Log.d("AnichinPlayer", "Found Dailymotion: $dmUrl")
            handleDailymotion(dmUrl, callback)
            return
        }

        Log.d("AnichinPlayer", "Checking for OkRu embed...")
        val okMatch = Regex("""src="(https://ok\.ru[^"]*)""").find(html)
        if (okMatch != null) {
            val okUrl = okMatch.groupValues[1]
            Log.d("AnichinPlayer", "Found OkRu: $okUrl")
            loadExtractor(okUrl, subtitleCallback, callback)
            return
        }

        Log.d("AnichinPlayer", "Falling back to generic iframe...")
        val anyIframe = doc.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
        if (anyIframe != null) {
            val iframeUrl = httpsify(anyIframe)
            Log.d("AnichinPlayer", "Generic iframe: $iframeUrl")
            loadExtractor(iframeUrl, subtitleCallback, callback)
            return
        }

        Log.w("AnichinPlayer", "No video source found")
        Log.d("AnichinPlayer", "HTML sample: ${html.take(3000)}")
    }

    private suspend fun handleDailymotion(dmUrl: String, callback: (ExtractorLink) -> Unit) {
        val videoId = Regex("[?&]video=([^&]+)").find(dmUrl)?.groupValues?.get(1)?.trim()
            ?: Regex("/video/([^/?]+)").find(dmUrl)?.groupValues?.get(1)?.trim()
            ?: run {
                Log.w("AnichinPlayer", "No video ID in: $dmUrl")
                return
            }
        Log.d("AnichinPlayer", "Video ID: $videoId")

        val metadataUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
        Log.d("AnichinPlayer", "Fetching metadata: $metadataUrl")
        val metaResponse = try {
            app.get(metadataUrl, headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://www.dailymotion.com/",
                "Origin" to "https://www.dailymotion.com",
                "Accept" to "application/json, text/plain, */*",
            ))
        } catch (e: Exception) {
            Log.w("AnichinPlayer", "Metadata fetch FAILED: ${e.message}")
            return
        }
        Log.d("AnichinPlayer", "Metadata fetched, size: ${metaResponse.text.length}")
        val metaText = metaResponse.text
        Log.d("AnichinPlayer", "Metadata: ${metaText.take(1500)}")

        if (metaText.contains("error") || metaText.contains("\"status\":2004") || metaText.contains("geoblocked", ignoreCase = true)) {
            Log.w("AnichinPlayer", "Video restricted/geo-blocked: $videoId")
            Log.d("AnichinPlayer", "Error response: ${metaText.take(500)}")
            return
        }

        val m3u8Urls = Regex(""""url"\s*:\s*"([^"]*\.m3u8[^"]*)""")
            .findAll(metaText)
            .map { it.groupValues[1].replace("\\/", "/").replace("\\u0026", "&") }
            .toList()

        Log.d("AnichinPlayer", "Found ${m3u8Urls.size} m3u8 URLs")
        if (m3u8Urls.isEmpty()) {
            val fallbackM3u8 = Regex("""https?://[^"'\s,]+\.m3u8[^"'\s,]*""")
                .find(metaText)?.value
            if (fallbackM3u8 != null) {
                Log.d("AnichinPlayer", "Fallback m3u8: ${fallbackM3u8.take(80)}...")
                verifyDmM3u8(fallbackM3u8, callback)
            } else {
                Log.w("AnichinPlayer", "No m3u8 at all in metadata")
            }
            return
        }

        m3u8Urls.forEach { verifyDmM3u8(it, callback) }
    }

    private suspend fun verifyDmM3u8(m3u8Url: String, callback: (ExtractorLink) -> Unit) {
        Log.d("AnichinPlayer", "Verifying: ${m3u8Url.take(80)}...")
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://www.dailymotion.com/",
            "Origin" to "https://www.dailymotion.com",
        )
        try {
            val resp = app.get(m3u8Url, headers = headers)
            Log.d("AnichinPlayer", "m3u8 size: ${resp.text.length}")
            val body = resp.text
            if (body.startsWith("#EXTM3U")) {
                val variants = Regex("#EXT-X-STREAM-INF").findAll(body).count()
                val segments = Regex("#EXTINF").findAll(body).count()
                Log.d("AnichinPlayer", "Valid: $variants variants, $segments segments")
                Regex("#EXT-X-STREAM-INF[^#]*BANDWIDTH=(\\d+)[^#]*\n([^#\n]+)").findAll(body).forEach { m ->
                    val bw = m.groupValues[1].toIntOrNull() ?: 0
                    val q = when {
                        bw >= 5000000 -> "1080p"
                        bw >= 2500000 -> "720p"
                        bw >= 1000000 -> "480p"
                        bw >= 500000  -> "360p"
                        else -> "240p"
                    }
                    Log.d("AnichinPlayer", "  ${bw}bps -> $q")
                }
            } else {
                Log.w("AnichinPlayer", "NOT m3u8: ${body.take(300)}")
                return
            }
        } catch (e: Exception) {
            Log.w("AnichinPlayer", "Verify FAILED: ${e.message}")
            return
        }
        callback.invoke(newExtractorLink(
            source = "AnichinPlayer",
            name = "AnichinPlayer",
            url = m3u8Url,
            type = ExtractorLinkType.M3U8
        ) {
            this.quality = Qualities.Unknown.value
            this.referer = "https://www.dailymotion.com/"
            this.headers = headers
        })
    }
}
