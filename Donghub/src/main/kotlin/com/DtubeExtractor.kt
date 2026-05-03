package com.donghub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app

open class DtubeExtractor : ExtractorApi() {
    override val mainUrl = "https://play.d.tube"
    override val requiresReferer = true
    override val name = "DTube"

    override val matchRegex = Regex(
        "(?:https?://)?(?:www\\.)?play\\.d\\.tube/.*"
    )
    override val matchDomains = listOf(
        "play.d.tube",
        "d.tube"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document

        val sourceEl = document.selectFirst("video source")
        var videoUrl = sourceEl?.attr("src")?.trim()

        if (!videoUrl.isNullOrBlank()) {
            val isM3u8 = videoUrl.contains(".m3u8")
            
            callback(
                ExtractorLink(
                    source = name,
                    name = "$name (Auto)",
                    url = videoUrl,
                    referer = url,
                    quality = Qualities.Unknown.value,
                    isM3u8 = isM3u8,
                    headers = mapOf(
                        "Referer" to url,
                        "Origin" to "https://play.d.tube"
                    )
                )
            )
        }
    }
}
