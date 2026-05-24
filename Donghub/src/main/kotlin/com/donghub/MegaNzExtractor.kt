package com.donghub

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.ByteBuffer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MegaNzExtractor : ExtractorApi() {
    override val name            = "Mega"
    override val mainUrl         = "https://mega.nz"
    override val requiresReferer = false

    companion object {
        private const val MEGA_API = "https://g.api.mega.co.nz/cs"

        fun megaB64Decode(s: String): ByteArray {
            val fixed = s.replace("-", "+").replace("_", "/")
            val pad   = (4 - fixed.length % 4) % 4
            return Base64.getDecoder().decode(fixed + "=".repeat(pad))
        }

        fun decodeFileKey(b64key: String): Pair<ByteArray, ByteArray> {
            val raw = megaB64Decode(b64key)
            val buf = ByteBuffer.wrap(raw)
            val k   = IntArray(8) { buf.int }

            fun intsToBytes(ints: IntArray): ByteArray {
                val b = ByteBuffer.allocate(ints.size * 4)
                ints.forEach { b.putInt(it) }
                return b.array()
            }

            val aesKey = intsToBytes(intArrayOf(
                k[0] xor k[4], k[1] xor k[5],
                k[2] xor k[6], k[3] xor k[7]
            ))
            val iv = intsToBytes(intArrayOf(k[4], k[5], 0, 0))
            return Pair(aesKey, iv)
        }

        fun decryptAttrs(encB64: String, aesKey: ByteArray): Map<String, String>? {
            return try {
                val enc = megaB64Decode(encB64)
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(aesKey, "AES"),
                    IvParameterSpec(ByteArray(16))
                )
                val dec  = cipher.doFinal(enc)
                val text = String(dec.takeWhile { it != 0.toByte() }.toByteArray(), Charsets.UTF_8)
                if (text.startsWith("MEGA")) {
                    val json = Json.parseToJsonElement(text.removePrefix("MEGA"))
                    json.jsonObject.mapValues { it.value.jsonPrimitive.content }
                } else null
            } catch (e: Exception) {
                println("⚠️ [MegaNz] decryptAttrs: ${e.message}")
                null
            }
        }
    }

    private fun normaliseUrl(url: String): String {
        var u = url.trim()
            .replace("$mainUrl/embed/", "$mainUrl/file/")
        u = u.replace(Regex("""/#!([^!]+)!(.+)"""), "/file/$1#$2")
        return u
    }

    private fun parseUrl(url: String): Pair<String, String>? {
        val norm    = normaliseUrl(url)
        val path    = norm.removePrefix("$mainUrl/file/")
        val nodeId  = path.substringBefore("#")
        val fileKey = path.substringAfter("#", "")
        if (nodeId.isBlank() || fileKey.isBlank()) return null
        return Pair(nodeId, fileKey)
    }

    private suspend fun getFileInfo(nodeId: String): JsonObject? {
        val body = """[{"a":"g","g":1,"p":"$nodeId"}]"""
        return try {
            val resp = app.post(
                "$MEGA_API?id=1",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Origin"       to "https://mega.nz",
                    "Referer"      to "https://mega.nz/"
                ),
                requestBody = body.toRequestBody("application/json".toMediaType())
            )
            val arr = Json.parseToJsonElement(resp.text).jsonArray
            if (arr.isNotEmpty() && arr[0] is JsonObject) arr[0].jsonObject else null
        } catch (e: Exception) {
            println("❌ [MegaNz] API: ${e.message}")
            null
        }
    }

    private fun guessQuality(fileName: String): Int {
        val n = fileName.lowercase()
        return when {
            "4k"   in n || "2160" in n -> Qualities.P2160.value
            "1080" in n                -> Qualities.P1080.value
            "720"  in n                -> Qualities.P720.value
            "480"  in n                -> Qualities.P480.value
            "360"  in n                -> Qualities.P360.value
            else                       -> Qualities.Unknown.value
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val (nodeId, fileKey) = parseUrl(url) ?: run {
            println("❌ [MegaNz] Gagal parse URL: $url")
            return
        }
        println("🎯 [MegaNz] node=$nodeId key=${fileKey.take(12)}...")

        val (aesKey, _) = try {
            decodeFileKey(fileKey)
        } catch (e: Exception) {
            println("❌ [MegaNz] decodeFileKey: ${e.message}")
            return
        }

        val finfo = getFileInfo(nodeId)

        if (finfo == null) {
            println("⚠️ [MegaNz] API gagal, fallback ke URL mentah")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name   = name,
                    url    = normaliseUrl(url),
                    type   = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                }
            )
            return
        }

        val dlUrl = finfo["g"]?.jsonPrimitive?.contentOrNull
        if (dlUrl.isNullOrBlank()) {
            println("❌ [MegaNz] Tidak ada download URL di response API")
            return
        }

        val encAt    = finfo["at"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val attrs    = if (encAt.isNotBlank()) decryptAttrs(encAt, aesKey) else null
        val fileName = attrs?.get("n").orEmpty()
        val ext      = fileName.substringAfterLast(".", "").lowercase()

        val linkType = when (ext) {
            "m3u8"              -> ExtractorLinkType.M3U8
            "mp4", "mkv",
            "webm", "m4v"      -> ExtractorLinkType.VIDEO
            else               -> ExtractorLinkType.VIDEO
        }

        val streamName = buildString {
            append(name)
            if (fileName.isNotBlank()) append(" · $fileName")
        }

        println("✅ [MegaNz] file=$fileName type=$linkType dlUrl=${dlUrl.take(55)}...")

        callback.invoke(
            newExtractorLink(
                source = name,
                name   = streamName,
                url    = dlUrl,
                type   = linkType
            ) {
                this.quality = guessQuality(fileName)
                this.referer = "$mainUrl/"
                this.headers = mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
                    "Origin" to mainUrl
                )
            }
        )
    }
}
