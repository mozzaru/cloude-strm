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

        private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

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
        val cfNative: String? = null,
        val title: String? = null,
        val poster: String? = null,
        val pk: Map<String, Any>? = null,
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("RpmShare", "getUrl: $url, referer: $referer")

            val videoId = when {
                url.contains("#") -> url.substringAfterLast("#").trim()
                url.contains("id=") -> url.substringAfter("id=").substringBefore("&").trim()
                url.contains("v=") -> url.substringAfter("v=").substringBefore("&").trim()
                url.contains("/embed/") -> url.substringAfter("/embed/").substringBefore("?").trim()
                url.contains("/e/") -> url.substringAfter("/e/").substringBefore("?").trim()
                else -> {
                    val lastPath = url.substringAfterLast("/").substringBefore("?").substringBefore("#")
                    lastPath.takeIf { it.isNotBlank() && !it.contains(".") }
                }
            }
            if (videoId.isNullOrBlank()) {
                Log.w("RpmShare", "Could not extract video ID from URL: $url")
                return
            }

            Log.d("RpmShare", "Extracted video ID: $videoId")

            try {
                Log.d("RpmShare", "Sending warm-up request to embed page...")
                app.get(
                    "$mainUrl/#$videoId",
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Referer" to (referer ?: "https://anichin.moe/"),
                    )
                )
                Log.d("RpmShare", "Warm-up done")
            } catch (e: Exception) {
                Log.d("RpmShare", "Warm-up request failed (non-fatal): ${e.message}")
            }

            val apiUrl = "$mainUrl/api/v1/video?id=$videoId&w=360&h=800"
            Log.d("RpmShare", "Fetching API: $apiUrl")

            val rawResponse = try {
                app.get(
                    apiUrl,
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl,
                        "sec-fetch-dest" to "empty",
                        "sec-fetch-mode" to "cors",
                        "sec-fetch-site" to "same-origin",
                    )
                )
            } catch (e: Exception) {
                Log.w("RpmShare", "API request FAILED: ${e.message}")
                return
            }
            val rawText = rawResponse.text.trim()
            Log.d("RpmShare", "API response size: ${rawText.length}")
            Log.d("RpmShare", "API response starts with: ${rawText.take(200)}")

            val jsonText = if (rawText.startsWith("{")) {
                Log.d("RpmShare", "Response is JSON, no decryption needed")
                rawText
            } else {
                Log.d("RpmShare", "Response is not JSON, attempting AES-CBC decryption...")
                val decrypted = decryptResponse(rawText)
                if (decrypted == null) {
                    Log.w("RpmShare", "Decryption FAILED")
                    Log.d("RpmShare", "Raw hex response length: ${rawText.length}")
                    return
                }
                Log.d("RpmShare", "Decryption SUCCESS, result: ${decrypted.take(200)}")
                decrypted
            }

            val json = try {
                Gson().fromJson(jsonText, RpmResponse::class.java)
            } catch (e: Exception) {
                Log.w("RpmShare", "JSON parse FAILED: ${e.message}")
                Log.d("RpmShare", "JSON text: ${jsonText.take(500)}")
                return
            }
            Log.d("RpmShare", "Parsed JSON: hlsVideoTiktok=${json.hlsVideoTiktok?.take(50)}, source=${json.source?.take(50)}, cf=${json.cf?.take(50)}")

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
            json.cfNative?.takeIf { it.isNotBlank() }?.let {
                streams.add("RpmShare CF Native" to it)
            }

            if (streams.isEmpty()) {
                Log.w("RpmShare", "No streams in response - all null/blank")
                return
            }

            streams.forEach { (streamName, streamUrl) ->
                Log.d("RpmShare", "Verifying stream: $streamName -> ${streamUrl.take(80)}...")
                try {
                    val verifyResp = app.get(streamUrl, headers = mapOf(
                        "User-Agent" to UA,
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl,
                    ))
                    Log.d("RpmShare", "  Stream size: ${verifyResp.text.length}")
                    val body = verifyResp.text
                    if (body.startsWith("#EXTM3U")) {
                        // A master playlist may contain a single variant OR be a media
                        // playlist directly (segments). Either is playable, so we only
                        // require the #EXTM3U signature rather than >1 variants.
                        val variants = Regex("#EXT-X-STREAM-INF").findAll(body).count()
                        val segments = Regex("#EXTINF").findAll(body).count()
                        Log.d("RpmShare", "  Valid m3u8: $variants variants, $segments segments")
                    } else {
                        Log.w("RpmShare", "  Response is NOT m3u8: ${body.take(200)}")
                    }
                } catch (e: Exception) {
                    Log.w("RpmShare", "  Stream verification FAILED: ${e.message}")
                }

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
        } catch (e: Exception) {
            Log.w("RpmShare", "UNCAUGHT EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }
}
