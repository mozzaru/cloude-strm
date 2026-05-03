package com.donghub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class DtubeExtractor : ExtractorApi() {
    override var mainUrl = "https://play.d.tube"
    override val requiresReferer = true
    override var name = "DTube"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = if (url.contains("?v=")) {
            url.substringAfter("?v=").substringBefore("&")
        } else {
            url.substringAfterLast("/")
        }

        if (videoId.isNotEmpty() && videoId.length > 10) {
            val videoUrl = "https://nas1.d.tube/videos/$videoId/master.m3u8"

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name (Auto)",
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = url
                }
            )
        }
    }
}
