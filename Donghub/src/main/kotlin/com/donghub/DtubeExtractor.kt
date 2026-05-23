package com.donghub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.math.BigInteger

open class DtubeExtractor : ExtractorApi() {
    override var mainUrl = "https://play.d.tube"
    override val requiresReferer = true
    override var name = "DTube"

    private val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun base58ToUuid(base58: String): String {
        var n = BigInteger.ZERO
        val rad = BigInteger.valueOf(58)
        for (char in base58) {
            val index = alphabet.indexOf(char)
            if (index == -1) return base58 // Not base58 or already UUID
            n = n.multiply(rad).add(BigInteger.valueOf(index.toLong()))
        }

        val hex = n.toString(16).padStart(32, '0')
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private fun isUuid(id: String): Boolean {
        return id.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE))
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

        val videoId = if (isUuid(rawId)) rawId else base58ToUuid(rawId)

        if (videoId.isNotEmpty()) {
            // Try nas1 and nas2 if needed, but nas1 is standard
            val videoUrl = "https://nas1.d.tube/videos/$videoId/master.m3u8"

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name (Auto)",
                    url = videoUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = url
                }
            )
        }
    }
}
