package com.Anichin

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.math.BigInteger

/**
 * D-Tube player used by Anichin (play.d.tube/?v=<id>).
 * Ported from the Donghub DtubeExtractor; resolves the base58/UUID video id to the
 * nas1/nas2 HLS master playlists and validates the body is actually an m3u8.
 */
open class Dtube : ExtractorApi() {
    override var mainUrl = "https://play.d.tube"
    override val requiresReferer = true
    override var name = "DTube"

    companion object {
        private const val TAG = "DtubeExtractor"
        private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        private const val TIMEOUT_MS = 8000L
        private val RESOLUTION_REGEX = Regex("RESOLUTION=\\d+x(\\d+)")
    }

    private fun base58ToUuid(base58: String): String {
        var n = BigInteger.ZERO
        for (char in base58) {
            val index = BASE58_ALPHABET.indexOf(char)
            if (index == -1) {
                Log.w(TAG, "Invalid base58 character: $char")
                return ""
            }
            n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(index.toLong()))
        }
        val hex = n.toString(16).padStart(32, '0')
        if (hex.length < 32) {
            Log.w(TAG, "Hex too short: $hex")
            return ""
        }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    private fun isUuid(id: String) = id.matches(
        Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE
        )
    )

    private data class CdnProbe(
        val label: String,
        val url: String,
        val ok: Boolean,
        val body: String? = null
    )

    private suspend fun probeCdn(
        url: String,
        label: String,
        headers: Map<String, String>,
        attempts: Int = 2
    ): CdnProbe {
        repeat(attempts) { attempt ->
            try {
                val res = app.get(url, headers = headers, allowRedirects = true, timeout = TIMEOUT_MS)
                Log.d(TAG, "$label attempt ${attempt + 1}: HTTP ${res.code}")
                if (res.code in 200..299 && res.text.contains("#EXTM3U")) {
                    return CdnProbe(label, url, true, res.text)
                }
                if (res.code in 200..299) {
                    Log.w(TAG, "$label balas 200 tapi body bukan m3u8 valid")
                } else {
                    Log.w(TAG, "$label HTTP ${res.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "$label attempt ${attempt + 1} error: ${e.message}")
            }
            if (attempt < attempts - 1) delay(300)
        }
        return CdnProbe(label, url, false)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "getUrl called with url=$url, referer=$referer")

        val rawId = when {
            "?v=" in url -> url.substringAfter("?v=").substringBefore("&")
            "v=" in url -> url.substringAfter("v=").substringBefore("&")
            else -> url.substringAfterLast("/").substringBefore("?").substringBefore("&")
        }.trim()

        if (rawId.isBlank()) {
            Log.w(TAG, "Cannot extract video ID from URL: $url")
            return
        }

        val isAlreadyUuid = isUuid(rawId)
        val videoId = if (isAlreadyUuid) rawId else base58ToUuid(rawId)
        if (videoId.isBlank()) {
            Log.w(TAG, "Failed to resolve video ID from: $rawId")
            return
        }

        val correctReferer = if (!isAlreadyUuid) "$mainUrl/?v=$rawId" else "$mainUrl/"
        val headers = mapOf(
            "Referer" to correctReferer,
            "Origin" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            "Accept" to "*/*",
        )

        val candidates = listOf(
            "https://nas1.d.tube/videos/$videoId/master.m3u8" to "nas1",
            "https://nas2.d.tube/videos/$videoId/master.m3u8" to "nas2",
        )

        val probes = coroutineScope {
            candidates.map { (u, label) -> async { probeCdn(u, label, headers) } }.awaitAll()
        }

        var foundCount = 0
        for (probe in probes) {
            if (!probe.ok) continue
            val bestResolution = probe.body
                ?.let { body -> RESOLUTION_REGEX.findAll(body).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() }
            val quality = bestResolution ?: Qualities.Unknown.value

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name (${probe.label})",
                    url = probe.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = quality
                    this.referer = correctReferer
                    this.headers = headers
                }
            )
            foundCount++
        }

        if (foundCount == 0 && !isAlreadyUuid) {
            val directUrl = "$mainUrl/videos/$rawId/master.m3u8"
            val probe = probeCdn(directUrl, "direct", headers, attempts = 1)
            if (probe.ok) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name (direct)",
                        url = directUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = correctReferer
                        this.headers = headers
                    }
                )
                foundCount++
            }
        }

        if (foundCount == 0) Log.w(TAG, "Tidak ada stream DTube ditemukan untuk video: $rawId")
    }
}
