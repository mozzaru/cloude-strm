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
            loadExtractor(dmUrl, subtitleCallback, callback)
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


}
