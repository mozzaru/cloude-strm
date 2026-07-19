package com.Anichin

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.google.gson.Gson
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class RpmShare : ExtractorApi() {
    override val name = "RpmShare"
    override val mainUrl = "https://anichin.rpmvid.com"
    override val requiresReferer = true

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
        private val AES_KEY = hexToBytes("6b69656d7469656e6d75613931316361")
        private val AES_IV = hexToBytes("313233343536373839306f6975797472")

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        private fun decryptResponse(hexStr: String): String? {
            val data = try { hexToBytes(hexStr.trim()) } catch (e: Exception) { return null }
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

    data class RpmResponse(
        val hlsVideoTiktok: String? = null,
        val source: String? = null,
        val cf: String? = null,
        val title: String? = null,
        val poster: String? = null,
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = when {
            url.contains("#") -> url.substringAfterLast("#").trim()
            url.contains("id=") -> url.substringAfter("id=").substringBefore("&").trim()
            else -> return
        }
        if (videoId.isBlank()) {
            Log.w("RpmShare", "No video ID in URL: $url")
            return
        }

        Log.d("RpmShare", "Video ID: $videoId")

        try {
            app.get(
                "$mainUrl/#$videoId",
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to (referer ?: "https://anichin.moe/"),
                    "sec-fetch-dest" to "iframe",
                    "sec-fetch-mode" to "navigate",
                    "sec-fetch-site" to "cross-site",
                )
            )
        } catch (_: Exception) {}

        val apiUrl = "$mainUrl/api/v1/video?id=$videoId&w=360&h=800&r=anichin.moe"
        Log.d("RpmShare", "Fetching API: $apiUrl")

        val rawResponse = app.get(
            apiUrl,
            headers = mapOf(
                "User-Agent" to UA,
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
                "sec-fetch-dest" to "empty",
                "sec-fetch-mode" to "cors",
                "sec-fetch-site" to "same-origin",
            )
        ).text.trim()

        val jsonText = if (rawResponse.startsWith("{")) {
            rawResponse
        } else {
            decryptResponse(rawResponse) ?: run {
                Log.w("RpmShare", "Failed to decrypt response")
                return
            }
        }

        val json = try {
            Gson().fromJson(jsonText, RpmResponse::class.java)
        } catch (e: Exception) {
            Log.w("RpmShare", "JSON parse failed: ${e.message}")
            return
        }

        val streams = mutableListOf<Pair<String, String>>()
        json.hlsVideoTiktok?.takeIf { it.isNotBlank() }?.let {
            val absUrl = if (it.startsWith("http")) it else "$mainUrl$it"
            streams.add("RpmShare Tiktok" to absUrl)
        }
        json.source?.takeIf { it.isNotBlank() }?.let {
            streams.add("RpmShare Source" to it)
        }
        json.cf?.takeIf { it.isNotBlank() }?.let {
            streams.add("RpmShare CF" to it)
        }

        if (streams.isEmpty()) {
            Log.w("RpmShare", "No streams found in response")
            return
        }

        streams.forEach { (streamName, streamUrl) ->
            Log.d("RpmShare", "Stream: $streamName -> ${streamUrl.take(80)}...")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = streamName,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                    this.headers = mapOf("User-Agent" to UA, "Origin" to mainUrl)
                }
            )
        }
    }
}
