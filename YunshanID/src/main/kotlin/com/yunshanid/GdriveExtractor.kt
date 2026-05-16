package com.yunshanid

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.SubtitleFile

class GdriveExtractor : ExtractorApi() {
    override var name = "Google Drive"
    override var mainUrl = "https://drive.google.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!url.contains("drive.google.com")) return

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to mainUrl
        )

        // Try to handle direct file IDs if possible
        val fileId = Regex("/file/d/([^/]+)").find(url)?.groupValues?.get(1)
            ?: Regex("id=([^&]+)").find(url)?.groupValues?.get(1)

        val finalUrl = if (fileId != null) {
            "https://drive.google.com/uc?id=$fileId&export=download"
        } else {
            url
        }

        val response = app.get(url, headers = headers)
        val document = response.text
        val map = Regex("\"fmt_stream_map\":\"(.*?)\"").find(document)?.groupValues?.get(1)

        if (map != null) {
            val decodedMap = map.replace("\\u0026", "&").replace("\\u003d", "=").replace("\\u002c", ",")
            decodedMap.split(",").forEach { stream ->
                val parts = stream.split("|")
                if (parts.size >= 2) {
                    val itag = parts[0].toIntOrNull() ?: 0
                    val streamUrl = parts[1]
                    val qualityName = when (itag) {
                        37 -> "1080p"
                        22 -> "720p"
                        59 -> "480p"
                        18 -> "360p"
                        else -> "${itag}p"
                    }
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            streamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = getQualityFromName(qualityName)
                            this.headers = headers
                        }
                    )
                }
            }
        } else {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    finalUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }
    }
}
