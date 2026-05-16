package com.yunshanid

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile

class Dailymotion : ExtractorApi() {
    override var name = "Dailymotion"
    override var mainUrl = "https://www.dailymotion.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = if (url.contains("video=")) {
            url.split("video=").last().split("&").first()
        } else {
            url.split("/").last()
        }

        val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"
        val response = app.get(apiUrl).text
        val m3u8Url = Regex("\"url\":\"(https://.*?m3u8.*?)\"").find(response)?.groupValues?.get(1)?.replace("\\/", "/")

        if (m3u8Url != null) {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    m3u8Url,
                    type = INFER_TYPE
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
