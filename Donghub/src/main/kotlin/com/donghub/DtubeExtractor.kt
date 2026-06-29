package com.donghub

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.math.BigInteger

/**
 * DtubeExtractor
 * ==============
 * Fix: referer sebelumnya hardcode ke "https://play.d.tube/" (tanpa ?v=ID).
 * CDN nas1/nas2 butuh referer dengan ?v=<base58_id> agar HLS request valid.
 * Tanpa referer yang benar ExoPlayer bisa throw ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (2001).
 */
open class DtubeExtractor : ExtractorApi() {
    override var mainUrl         = "https://play.d.tube"
    override val requiresReferer = true
    override var name            = "DTube"

    private val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun base58ToUuid(base58: String): String {
        var n = BigInteger.ZERO
        for (char in base58) {
            val index = alphabet.indexOf(char)
            if (index == -1) return ""
            n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(index.toLong()))
        }
        val hex = n.toString(16).padStart(32, '0')
        if (hex.length < 32) return ""
        return "${hex.substring(0,8)}-${hex.substring(8,12)}-" +
               "${hex.substring(12,16)}-${hex.substring(16,20)}-" +
               "${hex.substring(20,32)}"
    }

    private fun isUuid(id: String) = id.matches(
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE)
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Parse raw base58 ID dari URL
        // Contoh input:
        //   https://play.d.tube/?v=BXC71sLPVuRu72fuZ8K3hd
        //   https://play.d.tube?v=BXC71sLPVuRu72fuZ8K3hd
        //   BXC71sLPVuRu72fuZ8K3hd
        val rawId = when {
            url.contains("?v=") -> url.substringAfter("?v=").substringBefore("&")
            url.contains("/")   -> url.substringAfterLast("/").substringBefore("?")
            else                -> url
        }.trim()
        if (rawId.isBlank()) return

        val videoId = if (isUuid(rawId)) rawId else base58ToUuid(rawId)
        if (videoId.isBlank()) return

        // FIX: referer harus "https://play.d.tube/?v=<rawId>" (base58, bukan UUID)
        // Kalau input sudah UUID (tidak bisa rebuild base58), fallback ke bare URL
        val correctReferer = if (!isUuid(rawId)) "$mainUrl/?v=$rawId" else "$mainUrl/"

        val headers = mapOf(
            "Referer"         to correctReferer,
            "Origin"          to mainUrl,
            "User-Agent"      to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
            "Accept"          to "*/*",
            "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
        )

        listOf("nas1.d.tube" to "nas1", "nas2.d.tube" to "nas2").forEach { (cdn, label) ->
            callback(
                newExtractorLink(
                    source = name,
                    name   = "$name ($label)",
                    url    = "https://$cdn/videos/$videoId/master.m3u8",
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = correctReferer
                    this.headers = headers
                }
            )
        }
    }
}
