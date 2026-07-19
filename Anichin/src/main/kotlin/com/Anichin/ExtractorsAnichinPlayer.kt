package com.Anichin

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
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
        Log.d("AnichinPlayer", "Fetching proxy URL: $url")

        val response = app.get(url, referer = referer)
        val html = response.text
        val doc = Jsoup.parse(html)

        val dmMatch = Regex("""src="(https://geo\.dailymotion\.com[^"]*)""").find(html)
        if (dmMatch != null) {
            val dmUrl = dmMatch.groupValues[1].replace("&amp;", "&")
            Log.d("AnichinPlayer", "Found Dailymotion embed: $dmUrl")
            loadExtractor(dmUrl, subtitleCallback, callback)
            return
        }

        val okMatch = Regex("""src="(https://ok\.ru[^"]*)""").find(html)
        if (okMatch != null) {
            val okUrl = okMatch.groupValues[1]
            Log.d("AnichinPlayer", "Found OkRu embed: $okUrl")
            loadExtractor(okUrl, subtitleCallback, callback)
            return
        }

        val anyIframe = doc.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
        if (anyIframe != null) {
            val iframeUrl = httpsify(anyIframe)
            Log.d("AnichinPlayer", "Found iframe: $iframeUrl")
            loadExtractor(iframeUrl, subtitleCallback, callback)
            return
        }

        Log.w("AnichinPlayer", "No video source found in proxy page")
    }
}
