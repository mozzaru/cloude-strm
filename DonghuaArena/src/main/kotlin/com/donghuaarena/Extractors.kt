package com.donghuaarena

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.math.BigInteger

abstract class SimpleUniversalExtractor : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        android.util.Log.d("DonghuaArena", "[$name] getUrl dipanggil => $url")

        val res = try {
            app.get(url, referer = referer).text
        } catch (e: Exception) {
            android.util.Log.e("DonghuaArena", "[$name] Gagal fetch url: $url | error: ${e.message}")
            return
        }

        val mediaUrl = Regex("""file\s*[=:]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(res)?.groupValues?.get(1)
            ?: Regex("""src\s*[=:]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(res)?.groupValues?.get(1)
            ?: Regex("""source\s*[=:]\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(res)?.groupValues?.get(1)
            ?: Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(res)?.groupValues?.get(1)
            ?: Regex("""file\s*[=:]\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(res)?.groupValues?.get(1)

        android.util.Log.d("DonghuaArena", "[$name] mediaUrl ditemukan: $mediaUrl")

        if (mediaUrl != null) {
            val finalUrl = if (mediaUrl.startsWith("http")) mediaUrl else {
                val base = url.substringBeforeLast("/")
                if (mediaUrl.startsWith("/"))
                    url.substringBefore("/", "https://" + url.substringAfter("://").substringBefore("/")) + mediaUrl
                else "$base/$mediaUrl"
            }

            android.util.Log.d("DonghuaArena", "[$name] finalUrl: $finalUrl")

            callback(
                newExtractorLink(
                    name,
                    name,
                    finalUrl,
                    if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = url
                }
            )
        } else {
            android.util.Log.w("DonghuaArena", "[$name] mediaUrl TIDAK ditemukan di halaman: $url")
        }
    }
}

class StreamHls : SimpleUniversalExtractor() {
    override val name = "StreamHLS"
    override val mainUrl = "https://streamhls.to"
}

class LuluVdo : SimpleUniversalExtractor() {
    override val name = "LuluVdo"
    override val mainUrl = "https://luluvdo.com"
}

class LuluVid : SimpleUniversalExtractor() {
    override val name = "LuluVid"
    override val mainUrl = "https://luluvid.com"
}

class MyVidPlay : SimpleUniversalExtractor() {
    override val name = "MyVidPlay"
    override val mainUrl = "https://myvidplay.com"
}

class Byse : SimpleUniversalExtractor() {
    override val name = "Byse"
    override val mainUrl = "https://bysezejataos.com"
}

class TurboVid : SimpleUniversalExtractor() {
    override val name = "TurboVid"
    override val mainUrl = "https://turbovidhls.com"
}

class Vidara : SimpleUniversalExtractor() {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.so"
}

class Playmogo : SimpleUniversalExtractor() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
}

class Streamtape : SimpleUniversalExtractor() {
    override val name = "Streamtape"
    override val mainUrl = "https://streamtape.com"
}

class DoodStream : SimpleUniversalExtractor() {
    override val name = "DoodStream"
    override val mainUrl = "https://doodstream.com"
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
        android.util.Log.d("DonghuaArena", "[ArchiveOrg] getUrl dipanggil => $url")

        val doc = try {
            app.get(url).document
        } catch (e: Exception) {
            android.util.Log.e("DonghuaArena", "[ArchiveOrg] Gagal fetch: ${e.message}")
            return
        }

        doc.select("a[href*=\"/download/\"]").forEach {
            val href = it.attr("href")
            val mediaUrl = if (href.startsWith("http")) href else "https://archive.org$href"
            val label = it.text()
            android.util.Log.d("DonghuaArena", "[ArchiveOrg] link ditemukan: $mediaUrl")
            callback(
                newExtractorLink(
                    name,
                    label.ifBlank { name },
                    mediaUrl,
                    if (mediaUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = when {
                        mediaUrl.contains("1080") -> Qualities.P1080.value
                        mediaUrl.contains("720") -> Qualities.P720.value
                        else -> Qualities.Unknown.value
                    }
                    this.referer = url
                }
            )
        }

        doc.select("source[src*=\"/download/\"]").forEach {
            val src = it.attr("src")
            val mediaUrl = if (src.startsWith("http")) src else "https://archive.org$src"
            android.util.Log.d("DonghuaArena", "[ArchiveOrg] source ditemukan: $mediaUrl")
            callback(
                newExtractorLink(
                    name,
                    name,
                    mediaUrl,
                    if (mediaUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = when {
                        mediaUrl.contains("1080") -> Qualities.P1080.value
                        mediaUrl.contains("720") -> Qualities.P720.value
                        else -> Qualities.Unknown.value
                    }
                    this.referer = url
                }
            )
        }

        doc.select("play-av").attr("playlist").let { playlist ->
            if (playlist.isNotBlank()) {
                Regex("\"file\":\"(.*?)\"").findAll(playlist).forEach {
                    val file = it.groupValues[1]
                    val mediaUrl = if (file.startsWith("http")) file else "https://archive.org$file"
                    android.util.Log.d("DonghuaArena", "[ArchiveOrg] playlist file: $mediaUrl")
                    callback(
                        newExtractorLink(
                            name,
                            name,
                            mediaUrl,
                            if (mediaUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = when {
                                mediaUrl.contains("1080") -> Qualities.P1080.value
                                mediaUrl.contains("720") -> Qualities.P720.value
                                else -> Qualities.Unknown.value
                            }
                            this.referer = url
                        }
                    )
                }
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
        android.util.Log.d("DonghuaArena", "[DTube] getUrl dipanggil => $url")

        val rawId = if (url.contains("?v=")) {
            url.substringAfter("?v=").substringBefore("&")
        } else {
            url.substringAfterLast("/")
        }

        if (rawId.isBlank()) {
            android.util.Log.w("DonghuaArena", "[DTube] rawId kosong, skip")
            return
        }

        val videoId = if (rawId.contains("-")) rawId else base58ToUuid(rawId)
        val videoUrl = "https://nas1.d.tube/videos/$videoId/master.m3u8"

        android.util.Log.d("DonghuaArena", "[DTube] videoId: $videoId | videoUrl: $videoUrl")

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
