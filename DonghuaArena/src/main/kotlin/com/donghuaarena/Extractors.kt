package com.donghuaarena

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.api.Log
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

open class SimpleUniversalExtractor(override val name: String, override val mainUrl: String) : ExtractorApi() {
    override val requiresReferer: Boolean get() = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Universal", "getUrl: $url referer: $referer")
        val response = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text

        val m3u8Regex = """["'](https?://[^"']+\.m3u8[^"']*)["']""".toRegex()
        val m3u8Match = m3u8Regex.find(response)
        if (m3u8Match != null) {
            val link = m3u8Match.groupValues[1].replace("\\/", "/")
            Log.d("Universal", "Found m3u8: $link")
            callback.invoke(newExtractorLink(this.name, this.name, link, INFER_TYPE) {
                this.referer = referer ?: url
            })
            return
        }

        val unpacked = getAndUnpack(response)
        if (unpacked != response) {
            val packedMatch = m3u8Regex.find(unpacked)
            if (packedMatch != null) {
                val link = packedMatch.groupValues[1].replace("\\/", "/")
                Log.d("Universal", "Found m3u8 in packed: $link")
                callback.invoke(newExtractorLink(this.name, this.name, link, INFER_TYPE) {
                    this.referer = referer ?: url
                })
                return
            }
        }

        val videoRegex = """<video[^>]+src=["'](https?://[^"']+)["']""".toRegex()
        val videoMatch = videoRegex.find(response)
        if (videoMatch != null) {
            val link = videoMatch.groupValues[1]
            Log.d("Universal", "Found video src: $link")
            callback.invoke(newExtractorLink(this.name, this.name, link, INFER_TYPE) {
                this.referer = referer ?: url
                if (!link.contains(".m3u8")) this.quality = Qualities.Unknown.value
            })
        }
    }
}

class StreamHls : SimpleUniversalExtractor("StreamHls", "https://streamhls.my.id")
class LuluVid : SimpleUniversalExtractor("LuluVid", "https://luluvid.com")
class LuluVdo : SimpleUniversalExtractor("LuluVdo", "https://luluvdo.com")
class TurboVid : SimpleUniversalExtractor("TurboVid", "https://turbovid.eu")
class Vidara : SimpleUniversalExtractor("Vidara", "https://vidara.xyz")
class VidaraSo : SimpleUniversalExtractor("Vidara", "https://vidara.so")
class Playmogo : SimpleUniversalExtractor("Playmogo", "https://playmogo.com")

class MyVidPlay : DoodLaExtractor() {
    override var name: String = "DoodStream"
    override var mainUrl: String = "https://myvidplay.com"
}

class ArchiveOrg : ExtractorApi() {
    override val name: String get() = "Archive.org"
    override val mainUrl: String get() = "https://archive.org"
    override val requiresReferer: Boolean get() = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        callback.invoke(newExtractorLink(this.name, this.name, url, INFER_TYPE) {
            this.quality = Qualities.Unknown.value
        })
    }
}

class DTube : ExtractorApi() {
    override val name: String get() = "DTube"
    override val mainUrl: String get() = "https://play.d.tube"
    override val requiresReferer: Boolean get() = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.split("?v=").lastOrNull()?.split("&")?.firstOrNull()
        if (videoId != null) {
            val m3u8 = "https://nas1.d.tube/videos/$videoId/master.m3u8"
            callback.invoke(newExtractorLink(this.name, this.name, m3u8, INFER_TYPE) {
                this.quality = Qualities.Unknown.value
            })
        }
    }
}

class Byse : ExtractorApi() {
    override val name: String = "Byse"
    override val mainUrl: String = "https://bysezejataos.com"
    override val requiresReferer: Boolean = true

    companion object {
        private const val TAG = "Byse"
        private const val PLAYER_ORIGIN = "https://rupertisdivingintoocean.com"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.trimEnd('/').substringAfterLast("/")
        if (videoId.isBlank()) return
        Log.d(TAG, "videoId: $videoId")

        try {
            // Step 1: Challenge
            val ch = app.post(
                "$PLAYER_ORIGIN/api/videos/access/challenge",
                headers = mapOf("User-Agent" to USER_AGENT),
                requestBody = "{}".toRequestBody("application/json".toMediaType())
            ).parsed<ChallengeResponse>()
            Log.d(TAG, "Challenge OK: ${ch.challengeId}")

            // Step 2: Generate ECDSA P-256 keypair & sign
            val kpg = java.security.KeyPairGenerator.getInstance("EC")
            kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            val ecPub = kp.public as java.security.interfaces.ECPublicKey

            fun ByteArray.pad32(): ByteArray =
                if (size >= 32) takeLast(32).toByteArray()
                else ByteArray(32 - size) + this

            val x = ecPub.w.affineX.toByteArray().pad32().toBase64Url()
            val y = ecPub.w.affineY.toByteArray().pad32().toBase64Url()

            val signer = java.security.Signature.getInstance("SHA256withECDSA")
            signer.initSign(kp.private)
            signer.update(ch.nonce.toByteArray())
            val sigRaw = derToRaw(signer.sign())

            // Step 3: Attest
            val attestJson = """{"challenge_id":"${ch.challengeId}","nonce":"${ch.nonce}","signature":"${sigRaw.toBase64Url()}","public_key":{"kty":"EC","crv":"P-256","x":"$x","y":"$y"},"client":{"user_agent":"$USER_AGENT"},"storage":{},"attributes":{}}"""
            val attest = app.post(
                "$PLAYER_ORIGIN/api/videos/access/attest",
                headers = mapOf("User-Agent" to USER_AGENT),
                requestBody = attestJson.toRequestBody("application/json".toMediaType())
            ).parsed<AttestResponse>()
            Log.d(TAG, "Attest OK confidence=${attest.confidence}")

            // Step 4: Playback
            val playbackJson = """{"fingerprint":{"token":"${attest.token}","viewer_id":"${attest.viewerId}","device_id":"${attest.deviceId}","confidence":${attest.confidence}}}"""
            val pbResp = app.post(
                "$PLAYER_ORIGIN/api/videos/$videoId/playback",
                headers = mapOf("User-Agent" to USER_AGENT),
                requestBody = playbackJson.toRequestBody("application/json".toMediaType())
            ).parsed<PlaybackResponse>()

            // Step 5: Decrypt AES-256-GCM
            val enc = pbResp.playback ?: run { Log.e(TAG, "No playback"); return }
            val key = enc.keyParts.fold(byteArrayOf()) { acc, kp2 -> acc + kp2.fromBase64Url() }
            val iv = enc.iv.fromBase64Url()
            val payload = enc.payload.fromBase64Url()

            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, iv)
            )
            val decrypted = AppUtils.parseJson<DecryptedPlayback>(String(cipher.doFinal(payload)))

            decrypted.sources.forEach { source ->
                Log.d(TAG, "m3u8: ${source.url}")
                callback.invoke(newExtractorLink(name, name, source.url, INFER_TYPE) {
                    this.referer = "$PLAYER_ORIGIN/"
                    this.quality = source.height ?: Qualities.Unknown.value
                })
            }

        } catch (e: Exception) {
            Log.e(TAG, "Byse error: ${e.message}")
        }
    }

    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        val rLen = der[i + 1].toInt() and 0xFF
        val r = der.slice(i + 2 until i + 2 + rLen).toByteArray()
        i += 2 + rLen
        val sLen = der[i + 1].toInt() and 0xFF
        val s = der.slice(i + 2 until i + 2 + sLen).toByteArray()
        fun ByteArray.pad32() =
            if (size > 32) takeLast(32).toByteArray()
            else ByteArray(32 - size) + this
        return r.pad32() + s.pad32()
    }

    private fun ByteArray.toBase64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private fun String.fromBase64Url(): ByteArray =
        Base64.getUrlDecoder().decode(this)

    data class ChallengeResponse(
        @JsonProperty("challenge_id") val challengeId: String = "",
        @JsonProperty("nonce") val nonce: String = ""
    )

    data class AttestResponse(
        @JsonProperty("token") val token: String = "",
        @JsonProperty("viewer_id") val viewerId: String = "",
        @JsonProperty("device_id") val deviceId: String = "",
        @JsonProperty("confidence") val confidence: Double = 0.0
    )

    data class PlaybackResponse(
        @JsonProperty("playback") val playback: EncryptedPayload? = null
    )

    data class EncryptedPayload(
        @JsonProperty("key_parts") val keyParts: List<String> = emptyList(),
        @JsonProperty("iv") val iv: String = "",
        @JsonProperty("payload") val payload: String = ""
    )

    data class DecryptedPlayback(
        @JsonProperty("sources") val sources: List<VideoSource> = emptyList()
    )

    data class VideoSource(
        @JsonProperty("url") val url: String = "",
        @JsonProperty("height") val height: Int? = null,
        @JsonProperty("label") val label: String? = null
    )
}
