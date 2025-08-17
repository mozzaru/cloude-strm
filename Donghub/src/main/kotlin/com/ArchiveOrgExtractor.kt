package com.donghub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ArchiveOrgExtractor : ExtractorApi() {
    override val name = "ArchiveOrg"
    override val mainUrl = "https://archive.org"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val safeUrl = url.trim().replace(" ", "%20")
        val usedReferer = referer ?: mainUrl

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to usedReferer,
            "Accept" to "*/*"
        )

        try {
            val resp = app.get(safeUrl, headers = headers)
            val doc = resp.document

            // cari langsung video tag
            val videoSrc = doc.selectFirst("video source")?.attr("src")
                ?: doc.selectFirst("video")?.attr("src")

            if (!videoSrc.isNullOrBlank()) {
                var finalUrl = videoSrc
                if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
                if (!finalUrl.startsWith("http")) {
                    finalUrl = "${safeUrl.substringBeforeLast('/')}/$finalUrl"
                }
                finalUrl = finalUrl.replace(" ", "%20")

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = finalUrl,
                        type = if (finalUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = usedReferer
                        this.quality = getQualityFromName(finalUrl)
                    }
                )
                return // ⬅️ penting: stop setelah nemu link
            }
        } catch (e: Exception) {
            println("ArchiveOrgExtractor error: ${e.message}")
        }

        println("ArchiveOrgExtractor: no video src found for $safeUrl")
    }
}
