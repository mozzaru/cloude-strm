package com.yunshanid

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLDecoder

class GdriveExtractor : ExtractorApi() {
    override var name = "Google Drive"
    override var mainUrl = "https://drive.google.com"
    override val requiresReferer = false

    // Cocokkan ID dari berbagai format URL Drive
    private val fileIdRegex = listOf(
        Regex("/file/d/([a-zA-Z0-9_-]{10,})"),   // /file/d/ID/preview atau /file/d/ID/view
        Regex("[?&]id=([a-zA-Z0-9_-]{10,})"),    // ?id=ID atau &id=ID
        Regex("/open\\?id=([a-zA-Z0-9_-]{10,})") // /open?id=ID
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("GdriveExtractor", "=== getUrl: $url ===")

        val fileId = fileIdRegex.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
        if (fileId == null) {
            Log.e("GdriveExtractor", "Gagal ekstrak file ID dari: $url")
            return
        }
        Log.d("GdriveExtractor", "File ID: $fileId")

        val success = tryGetVideoInfo(fileId, callback)

        if (!success) {
            Log.w("GdriveExtractor", "Semua strategi gagal untuk fileId=$fileId")
        }
    }

    private suspend fun tryGetVideoInfo(
        fileId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val apiUrl = "https://drive.google.com/get_video_info" +
            "?docid=$fileId&authuser=0&el=embedded&ps=docs"

        Log.d("GdriveExtractor", "Fetching: $apiUrl")

        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Safari/537.36",
                "Referer" to "https://drive.google.com/"
            )

            val response = app.get(apiUrl, headers = headers).text
            Log.d("GdriveExtractor", "Response length: ${response.length}")

            // Parse application/x-www-form-urlencoded
            val params = response.split("&").mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx == -1) null
                else part.substring(0, idx) to
                    URLDecoder.decode(part.substring(idx + 1), "UTF-8")
            }.toMap()

            val status = params["status"]
            Log.d("GdriveExtractor", "Status: $status")

            if (status != "ok") {
                Log.e("GdriveExtractor", "Status=$status, reason=${params["reason"]}")
                return false
            }

            val playerResponseStr = params["player_response"] ?: run {
                Log.e("GdriveExtractor", "Tidak ada player_response")
                return false
            }

            val pr = parseJson<PlayerResponse>(playerResponseStr)
            val formats = pr.streamingData?.formats ?: emptyList()

            Log.d("GdriveExtractor", "Jumlah muxed formats: ${formats.size}")

            if (formats.isEmpty()) {
                Log.w("GdriveExtractor", "formats kosong")
                return false
            }

            // Urutkan kualitas tertinggi dulu
            val sorted = formats.sortedByDescending { itagToQualityValue(it.itag ?: 0) }

            for (fmt in sorted) {
                val streamUrl = fmt.url ?: continue
                val itag = fmt.itag ?: continue
                val qualityName = fmt.qualityLabel ?: itagToName(itag)
                val quality = itagToQuality(itag)

                Log.d("GdriveExtractor", "Menambahkan stream: itag=$itag ($qualityName)")

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name $qualityName",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.referer = "https://drive.google.com/"
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/124.0.0.0 Safari/537.36",
                            "Referer" to "https://drive.google.com/"
                        )
                    }
                )
            }

            true
        } catch (e: Exception) {
            Log.e("GdriveExtractor", "Exception: ${e.message}")
            false
        }
    }

    // itag 37=1080p, 22=720p, 18=360p (semua muxed video+audio)
    private fun itagToQualityValue(itag: Int) = when (itag) {
        37 -> 3
        22 -> 2
        18 -> 1
        else -> 0
    }

    private fun itagToQuality(itag: Int) = when (itag) {
        37 -> Qualities.P1080.value
        22 -> Qualities.P720.value
        18 -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun itagToName(itag: Int) = when (itag) {
        37 -> "1080p"
        22 -> "720p"
        18 -> "360p"
        else -> "Auto"
    }

    data class PlayerResponse(
        @JsonProperty("streamingData") val streamingData: StreamingData?
    )

    data class StreamingData(
        @JsonProperty("formats") val formats: List<Format>?,
        @JsonProperty("adaptiveFormats") val adaptiveFormats: List<Format>?
    )

    data class Format(
        @JsonProperty("itag") val itag: Int?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("mimeType") val mimeType: String?,
        @JsonProperty("quality") val quality: String?,
        @JsonProperty("qualityLabel") val qualityLabel: String?
    )
}
