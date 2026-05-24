package com.donghub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.math.BigInteger


open class DtubeExtractor : ExtractorApi() {
    override var mainUrl          = "https://play.d.tube"
    override val requiresReferer  = true
    override var name             = "DTube"

    private val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun base58ToUuid(base58: String): String {
        var n = BigInteger.ZERO
        val rad = BigInteger.valueOf(58)
        for (char in base58) {
            val index = alphabet.indexOf(char)
            if (index == -1) return ""
            n = n.multiply(rad).add(BigInteger.valueOf(index.toLong()))
        }
        val hex = n.toString(16).padStart(32, '0')
        if (hex.length < 32) return ""
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-" +
               "${hex.substring(12, 16)}-${hex.substring(16, 20)}-" +
               "${hex.substring(20, 32)}"
    }

    private fun isUuid(id: String): Boolean {
        return id.matches(
            Regex(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
                RegexOption.IGNORE_CASE
            )
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val rawId = when {
            url.contains("?v=") -> url.substringAfter("?v=").substringBefore("&")
            else                -> url.substringAfterLast("/").substringBefore("?")
        }
        if (rawId.isBlank()) return

        val videoId = if (isUuid(rawId)) rawId else base58ToUuid(rawId)
        if (videoId.isBlank()) return

        val headers = mapOf(
            "Referer"          to "$mainUrl/",
            "Origin"           to mainUrl,
            "User-Agent"       to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
            "Accept"           to "*/*",
            "Accept-Language"  to "id-ID,id;q=0.9,en;q=0.8",
            "Connection"       to "keep-alive"
        )

        val cdnList = listOf(
            "nas1.d.tube" to "nas1",
            "nas2.d.tube" to "nas2",
        )

        for ((cdn, label) in cdnList) {
            val m3u8Url = "https://$cdn/videos/$videoId/master.m3u8"
            callback(
                newExtractorLink(
                    source = name,
                    name   = "$name ($label)",
                    url    = m3u8Url,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.quality  = Qualities.Unknown.value
                    this.referer  = "$mainUrl/"
                    this.headers  = headers
                }
            )
        }
    }
}
