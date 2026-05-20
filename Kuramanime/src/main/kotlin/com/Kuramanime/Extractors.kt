package com.Kuramanime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class KuramadriveS1 : ExtractorApi() {
    override val name: String = "Kuramadrive S1"
    override val mainUrl: String = "https://v18.kuramanime.ing"
    override val requiresReferer: Boolean = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        val hlsSrc = document.selectFirst("#player")?.attr("data-hls-src")
        if (hlsSrc != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = hlsSrc,
                    type = ExtractorLinkType.M3U8
                ) {
                    quality = Qualities.Unknown.value
                    this.referer = url
                }
            )
        }
    }
}

class RPMShare : ExtractorApi() {
    override val name: String = "RPMShare"
    override val mainUrl: String = "https://rpmshare.com"
    override val requiresReferer: Boolean = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        val script = document.select("script").find { it.html().contains("sources:") } ?: return
        val videoUrl = Regex("""file:\s*"([^"]+)"""").find(script.html())?.groupValues?.get(1) ?: return

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                quality = Qualities.Unknown.value
                this.referer = url
            }
        )
    }
}
