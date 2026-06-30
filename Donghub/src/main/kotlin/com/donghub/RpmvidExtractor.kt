package com.donghub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.google.gson.Gson
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class RpmvidExtractor : ExtractorApi() {
    override val name = "Rpmvid"
    override val mainUrl = "https://anichin.rpmvid.com"
    override val requiresReferer = true

    companion object {
        private const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/149.0.0.0 Mobile Safari/537.36"

        private val AES_KEY = hexToBytes("6b69656d7469656e6d75613931316361")
        private val AES_IV = hexToBytes("313233343536373839306f6975797472")

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        fun decryptResponse(hexStr: String): String? {
            val data = try {
                hexToBytes(hexStr.trim())
            } catch (e: Exception) { return null }

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(AES_KEY, "AES")
            val ivSpec = IvParameterSpec(AES_IV)

            for (skip in listOf(0, 4)) {
                val ct = if (skip > 0) data.copyOfRange(skip, data.size) else data
                if (ct.size % 16 != 0) continue
                return try {
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                    cipher.doFinal(ct).toString(Charsets.UTF_8).trim()
                } catch (e: Exception) { continue }
            }
            return null
        }
    }

    data class RpmvidResponse(
        val hlsVideoTiktok : String? = null,
        val source : String? = null,
        val cf : String? = null,
        val title : String? = null,
        val poster : String? = null,
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        MegaNzExtractor.stopAll()

        val videoId = when {
            url.contains("#") -> url.substringAfterLast("#").trim()
            url.contains("id=") -> url.substringAfter("id=").substringBefore("&").trim()
            else -> return
        }
        if (videoId.isBlank()) return

        try {
            app.get(
                "$mainUrl/#$videoId",
                headers = mapOf(
                    "User-Agent"      to UA_MOBILE,
                    "Referer"         to (referer ?: "https://donghub.vip/"),
                    "sec-fetch-dest"  to "iframe",
                    "sec-fetch-mode"  to "navigate",
                    "sec-fetch-site"  to "cross-site",
                )
            )
        } catch (_: Exception) {}

        val apiUrl = "$mainUrl/api/v1/video?id=$videoId&w=360&h=800&r=donghub.vip"
        val rawResponse = app.get(
            apiUrl,
            headers = mapOf(
                "User-Agent"      to UA_MOBILE,
                "Referer"         to "$mainUrl/",
                "Origin"          to mainUrl,
                "sec-fetch-dest"  to "empty",
                "sec-fetch-mode"  to "cors",
                "sec-fetch-site"  to "same-origin",
            )
        ).text.trim()

        val jsonText: String = if (rawResponse.startsWith("{")) {
            rawResponse
        } else {
            decryptResponse(rawResponse) ?: return
        }

        // FIX 1: Use Gson directly instead of tryParseJson
        val json: RpmvidResponse = try {
            Gson().fromJson(jsonText, RpmvidResponse::class.java)
        } catch (e: Exception) {
            return
        } ?: return

        val streams = mutableListOf<Pair<String, String>>()

        // FIX 2: Explicit String type on takeIf to avoid type inference errors
        val tiktokPath: String? = json.hlsVideoTiktok
        if (!tiktokPath.isNullOrBlank()) {
            val absUrl = if (tiktokPath.startsWith("http")) tiktokPath else "$mainUrl$tiktokPath"
            streams.add("Rpmvid Tiktok" to absUrl)
        }

        val sourcePath: String? = json.source
        if (!sourcePath.isNullOrBlank()) {
            streams.add("Rpmvid Source" to sourcePath)
        }

        val cfPath: String? = json.cf
        if (!cfPath.isNullOrBlank()) {
            streams.add("Rpmvid CF" to cfPath)
        }

        streams.forEach { (streamName, streamUrl) ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = streamName,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "User-Agent" to UA_MOBILE,
                        "Origin"     to mainUrl,
                    )
                }
            )
        }
    }
}
