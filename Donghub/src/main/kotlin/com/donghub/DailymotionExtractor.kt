package com.donghub

import com.google.gson.Gson
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI


class DonghubGeodailymotion : DonghubDailymotion() {
    override val name = "GeoDailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

open class DonghubDailymotion : ExtractorApi() {
    override val mainUrl = "https://www.dailymotion.com"
    override val name = "Dailymotion"
    override val requiresReferer = false
    private val baseUrl = "https://www.dailymotion.com"

    private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

    private val playerHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    // Map kualitas ke integer value langsung (tanpa enum)
    private fun getQualityInt(qualityKey: String): Int {
        return when (qualityKey) {
            "2160" -> 2160
            "1080" -> 1080
            "720"  -> 720
            "480"  -> 480
            "380"  -> 360
            "240"  -> 240
            "144"  -> 144
            else   -> Qualities.Unknown.value
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = getEmbedUrl(url) ?: return
        val id = getVideoId(embedUrl) ?: return

        // Visit embed page dulu untuk mendapatkan cookies valid
        val embedPage = app.get(
            "$baseUrl/embed/video/$id",
            referer = referer ?: baseUrl,
            headers = playerHeaders
        )

        val metaDataUrl = "$baseUrl/player/metadata/video/$id"

        val response = app.get(
            metaDataUrl,
            referer = "$baseUrl/embed/video/$id",
            headers = playerHeaders,
            cookies = embedPage.cookies
        ).text

        val gson = Gson()
        val meta = gson.fromJson(response, MetaData::class.java)

        val processedQualities = mutableSetOf<String>()
        val qualityOrder = listOf("2160", "1080", "720", "480", "380", "240", "144")

        // 1. Prioritaskan MP4 progressive (muxed audio+video, tidak lag)
        for (qualityKey in qualityOrder) {
            val qualityList = meta.qualities?.get(qualityKey) ?: continue
            for (quality in qualityList) {
                val videoUrl = quality.url ?: continue
                if (videoUrl.isBlank()) continue

                if (videoUrl.contains(".mp4") && !videoUrl.contains(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} ${qualityKey}p",
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$baseUrl/"
                            this.quality = getQualityInt(qualityKey)
                            this.headers = mapOf(
                                "User-Agent" to playerHeaders["User-Agent"]!!
                            )
                        }
                    )
                    processedQualities.add(qualityKey)
                }
            }
        }

        // 2. Fallback ke M3U8 "auto" jika tidak ada MP4
        if (processedQualities.isEmpty()) {
            meta.qualities?.get("auto")?.forEach { quality ->
                val videoUrl = quality.url
                if (!videoUrl.isNullOrEmpty() && videoUrl.contains(".m3u8")) {
                    try {
                        generateM3u8(
                            source = this.name,
                            streamUrl = videoUrl,
                            referer = "$baseUrl/",
                        ).forEach { link ->
                            callback.invoke(link)
                        }
                    } catch (e: Exception) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = videoUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$baseUrl/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
        }

        // 3. Last resort: coba semua kualitas M3U8
        if (processedQualities.isEmpty()) {
            for (qualityKey in qualityOrder) {
                val qualityList = meta.qualities?.get(qualityKey) ?: continue
                for (quality in qualityList) {
                    val videoUrl = quality.url ?: continue
                    if (videoUrl.isBlank()) continue

                    if (videoUrl.contains(".m3u8")) {
                        try {
                            generateM3u8(
                                source = this.name,
                                streamUrl = videoUrl,
                                referer = "$baseUrl/",
                            ).forEach { link ->
                                callback.invoke(link)
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
        }

        // Handle subtitles
        meta.subtitles?.data?.forEach { (_, subData) ->
            subData.urls.forEach { subUrl ->
                if (subUrl.isNotBlank()) {
                    subtitleCallback(
                        newSubtitleFile(
                            subData.label,
                            subUrl
                        )
                    )
                }
            }
        }
    }

    private fun getEmbedUrl(url: String): String? {
        if (url.contains("/embed/") || url.contains("/video/")) return url
        if (url.contains("geo.dailymotion.com")) {
            val videoId = if (url.contains("video=")) {
                url.substringAfter("video=").substringBefore("&")
            } else {
                url.substringAfterLast("/").substringBefore("?")
            }
            return "$baseUrl/embed/video/$videoId"
        }
        val possibleId = url.substringAfterLast("/").substringBefore("?")
        if (possibleId.matches(videoIdRegex)) {
            return "$baseUrl/embed/video/$possibleId"
        }
        return null
    }

    private fun getVideoId(url: String): String? {
        val path = URI(url).path
        val id = when {
            path.contains("/embed/video/") -> path.substringAfter("/embed/video/")
            path.contains("/video/")       -> path.substringAfter("/video/")
            else                           -> path.substringAfterLast("/")
        }.substringBefore("?").substringBefore("/")

        return if (id.matches(videoIdRegex)) id else null
    }

    data class MetaData(
        val qualities: Map<String, List<Quality>>?,
        val subtitles: SubtitlesWrapper?
    )

    data class Quality(
        val type: String?,
        val url: String?
    )

    data class SubtitlesWrapper(
        val enable: Boolean,
        val data: Map<String, SubtitleData>?
    )

    data class SubtitleData(
        val label: String,
        val urls: List<String>
    )
}
