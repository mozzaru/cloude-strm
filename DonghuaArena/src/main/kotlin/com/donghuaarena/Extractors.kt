package com.donghuaarena

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.math.BigInteger

class StreamHls : ExtractorApi() {
    override val name = "StreamHLS"
    override val mainUrl = "https://streamhls.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer).text
        val m3u8 = Regex("file:\"(.*?\\.m3u8)\"").find(res)?.groupValues?.get(1)
        if (m3u8 != null) {
            callback(
                newExtractorLink(
                    name,
                    name,
                    m3u8,
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = url
                }
            )
        }
    }
}

class LuluVdo : ExtractorApi() {
    override val name = "LuluVdo"
    override val mainUrl = "https://luluvdo.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer).text
        val m3u8 = Regex("sources:\\[\\{file:\"(.*?)\"").find(res)?.groupValues?.get(1)
        if (m3u8 != null) {
            callback(
                newExtractorLink(
                    name,
                    name,
                    m3u8,
                    if (m3u8.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = url
                }
            )
        }
    }
}

class MyVidPlay : ExtractorApi() {
    override val name = "MyVidPlay"
    override val mainUrl = "https://myvidplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer).text
        val m3u8 = Regex("sources:\\[\\{file:\"(.*?)\"").find(res)?.groupValues?.get(1)
        if (m3u8 != null) {
            callback(
                newExtractorLink(
                    name,
                    name,
                    m3u8,
                    if (m3u8.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = url
                }
            )
        }
    }
}

class Byse : ExtractorApi() {
    override val name = "Byse"
    override val mainUrl = "https://bysezejataos.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url, referer = referer).text
        val m3u8 = Regex("sources:\\[\\{file:\"(.*?)\"").find(res)?.groupValues?.get(1)
        if (m3u8 != null) {
            callback(
                newExtractorLink(
                    name,
                    name,
                    m3u8,
                    if (m3u8.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = url
                }
            )
        }
    }
}

class ArchiveOrg : ExtractorApi() {
    override val mainUrl = "https://archive.org"
    override val requiresReferer = false
    override val name = "Internet Archive"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).document

        doc.select("a[href*=\"/download/\"]").forEach {
            val mediaUrl = mainUrl + it.attr("href")
            if (mediaUrl.endsWith(".mp4") || mediaUrl.endsWith(".mkv")) {
                val quality = if (mediaUrl.contains("1080")) Qualities.P1080.value
                             else if (mediaUrl.contains("720")) Qualities.P720.value
                             else Qualities.Unknown.value

                callback(
                    newExtractorLink(
                        name,
                        it.text().ifBlank { name },
                        mediaUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.referer = url
                    }
                )
            }
        }

        if (doc.select("a[href*=\"/download/\"]").isEmpty()) {
            doc.select("meta[property=\"og:video\"]").firstOrNull()?.attr("content")?.let { mediaUrl ->
                callback(
                    newExtractorLink(
                        name,
                        name,
                        mediaUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = url
                    }
                )
            }
        }
    }
}

class DTube : ExtractorApi() {
    override var mainUrl = "https://play.d.tube"
    override val requiresReferer = true
    override var name = "DTube"

    private val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun base58ToUuid(base58: String): String {
        var n = BigInteger.ZERO
        val rad = BigInteger.valueOf(58)
        for (char in base58) {
            val index = alphabet.indexOf(char)
            if (index == -1) return base58
            n = n.multiply(rad).add(BigInteger.valueOf(index.toLong()))
        }
        val hex = n.toString(16).padStart(32, '0')
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val rawId = if (url.contains("?v=")) {
            url.substringAfter("?v=").substringBefore("&")
        } else {
            url.substringAfterLast("/")
        }
        if (rawId.isBlank()) return
        val videoId = if (rawId.contains("-")) rawId else base58ToUuid(rawId)
        val videoUrl = "https://nas1.d.tube/videos/$videoId/master.m3u8"
        callback(
            newExtractorLink(
                name,
                name,
                videoUrl,
                ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = url
            }
        )
    }
}
