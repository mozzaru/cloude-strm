package com.yunshanid

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.SubtitleFile

class OkRu : ExtractorApi() {
    override val name            = "OkRu"
    override val mainUrl         = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val currentHeaders = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "User-Agent" to USER_AGENT,
        )
        val embedUrl = if (url.contains("/videoembed/")) url else url.replace("/video/","/videoembed/")
        val videoReq  = app.get(embedUrl, headers=currentHeaders).text.replace("\\&quot;", "\"").replace("\\\\", "\\")
            .replace(Regex("\\\\u([0-9A-Fa-f]{4})")) { matchResult ->
                Integer.parseInt(matchResult.groupValues[1], 16).toChar().toString()
            }

        val videosStr = Regex(""""videos":(\[[^]]*])""").find(videoReq)?.groupValues?.get(1) ?: return
        val videos    = AppUtils.tryParseJson<List<OkRuVideo>>(videosStr) ?: return

        for (video in videos) {
            val videoUrl  = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            val qualityLabel   = video.name.uppercase()
                .replace("MOBILE", "144p")
                .replace("LOWEST", "240p")
                .replace("LOW",    "360p")
                .replace("SD",     "480p")
                .replace("HD",     "720p")
                .replace("FULL",   "1080p")
                .replace("QUAD",   "1440p")
                .replace("ULTRA",  "4k")

            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    videoUrl,
                    type = INFER_TYPE
                ) {
                    this.quality = getQualityFromName(qualityLabel)
                    this.headers = currentHeaders
                }
            )
        }
    }

    data class OkRuVideo(
        @JsonProperty("name") val name: String,
        @JsonProperty("url")  val url: String,
    )
}
