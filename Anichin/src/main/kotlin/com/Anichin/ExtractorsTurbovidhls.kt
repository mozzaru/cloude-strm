package com.Anichin

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class Turbovidhls : ExtractorApi() {
    override val name = "Turbovidhls"
    override val mainUrl = "https://turbovidhls.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Turbovidhls", "Fetching: $url")
        val response = app.get(url, referer = referer)
        val html = response.text

        val hashMatch = Regex("""data-hash="([^"]+)""").find(html)
        if (hashMatch != null) {
            val m3u8Url = hashMatch.groupValues[1]
            Log.d("Turbovidhls", "Found m3u8: $m3u8Url")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        } else {
            Log.w("Turbovidhls", "No m3u8 found in page")
        }
    }
}
