package com.donghuahub

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Dailymotion : ExtractorApi() {
    override val name = "Dailymotion"
    override val mainUrl = "https://www.dailymotion.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = if (url.contains("video=")) {
            url.substringAfter("video=").substringBefore("&")
        } else {
            url.substringAfterLast("/").substringBefore("?").substringBefore(".")
        }

        val metadataUrl = "$mainUrl/player/metadata/video/$videoId"
        val response = app.get(metadataUrl).parsedSafe<MetadataResponse>()

        response?.qualities?.auto?.forEach { stream ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = stream.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }

    data class MetadataResponse(
        val qualities: QualitiesMap?
    )

    data class QualitiesMap(
        val auto: List<Stream>?
    )

    data class Stream(
        val type: String,
        val url: String
    )
}
