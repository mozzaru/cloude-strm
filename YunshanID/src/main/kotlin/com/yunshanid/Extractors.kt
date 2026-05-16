package com.yunshanid

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.USER_AGENT

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
                    // Use internal method if isM3u8 is val
                }
            )
        }
    }
}

class GdriveExtractor : ExtractorApi() {
    override var name = "Gdrive"
    override var mainUrl = "https://drive.google.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).text
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
                            type = INFER_TYPE
                        ) {
                            this.quality = getQualityFromName(qualityName)
                        }
                    )
                }
            }
        } else {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    url,
                    type = INFER_TYPE
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}

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
