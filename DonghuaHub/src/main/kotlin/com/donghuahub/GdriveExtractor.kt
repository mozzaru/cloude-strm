package com.donghuahub

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class GdriveExtractor : ExtractorApi() {
    override val name = "Google Drive"
    override val mainUrl = "https://drive.google.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = url.substringAfter("/d/").substringBefore("/")
            .ifBlank { url.substringAfter("id=").substringBefore("&") }

        if (fileId.isBlank() || fileId.contains("http")) return

        val downloadUrl = "https://docs.google.com/uc?export=download&id=$fileId"
        val response = app.get(downloadUrl)

        var finalUrl = downloadUrl
        if (response.text.contains("confirm=")) {
            val confirmToken = response.text.substringAfter("confirm=").substringBefore("&")
            finalUrl += "&confirm=$confirmToken"
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = finalUrl
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P1080.value
            }
        )
    }
}
