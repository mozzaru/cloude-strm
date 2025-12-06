package com.Donghub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE

class NeoVideo : ExtractorApi() {
    override val name = "NeoVideo"
    override val mainUrl = "https://nos.jkt-1.neo.id"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        Log.d("NeoVideo", "Input URL = $url")

        val link = newExtractorLink(
            source = name,
            name = name,
            url = url,
            type = INFER_TYPE
        ).apply {
            // semua header dan referer dimasukkan di sini
            this.referer = "https://donghub.vip/"
            this.headers = mapOf(
                "Referer" to "https://donghub.vip/",
                "Range" to "bytes=0-",
                "Accept-Language" to "id-ID,id;q=0.9"
            )
            this.quality = 0
        }

        callback(link)
    }
}
