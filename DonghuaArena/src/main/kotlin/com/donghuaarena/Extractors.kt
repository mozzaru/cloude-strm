package com.donghuaarena

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.math.BigInteger

// ============================================================
// Streamtape — format khusus: gabung 2 string dari JS
// ============================================================
class Streamtape : ExtractorApi() {
    override val name = "Streamtape"
    override val mainUrl = "https://streamtape.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        android.util.Log.d("DonghuaArena", "[Streamtape] getUrl => $url")
        val res = app.get(url).text

        // Streamtape pakai 2 bagian JS yang digabung
        val id1 = Regex("""id=([^&"']+)""").find(res)?.groupValues?.get(1) ?: return
        val token = Regex("""token=([^&"']+)""").find(res)?.groupValues?.get(1)

        // Cari pola: robotlink atau videolink
        val videoUrl = Regex("""(https://streamtape\.com/get_video\?[^"'\s<]+)""").find(res)?.groupValues?.get(1)
            ?: Regex("""document\.getElementById\('robotlink'\)\.innerHTML = '([^']+)'\s*\+\s*'([^']+)'""").find(res)?.let {
                "https:" + it.groupValues[1] + it.groupValues[2]
            }
            ?: run {
                // Fallback: cari 2 bagian dan gabung
                val part1 = Regex("""innerHTML = "([^"]+)"""").find(res)?.groupValues?.get(1) ?: ""
                val part2 = Regex("""innerHTML \+= "([^"]+)"""").find(res)?.groupValues?.get(1) ?: ""
                if (part1.isNotBlank()) "https:$part1$part2" else null
            } ?: return

        android.util.Log.d("DonghuaArena", "[Streamtape] videoUrl: $videoUrl")
        callback(
            newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                this.quality = Qualities.Unknown.value
                this.referer = url
            }
        )
    }
}

// ============================================================
// MyVidPlay / DoodStream — pakai /pass_md5/ redirect
// ============================================================
class MyVidPlay : ExtractorApi() {
    override val name = "MyVidPlay"
    override val mainUrl = "https://myvidplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        android.util.Log.d("DonghuaArena", "[MyVidPlay] getUrl => $url")
        val res = app.get(url, referer = referer).text

        // DoodStream/MyVidPlay style: /pass_md5/
        val md5 = Regex("""\$\.get\(['"]([^'"]+/pass_md5/[^'"]+)['"]""").find(res)?.groupValues?.get(1)
            ?: Regex("""pass_md5/([^"']+)""").find(res)?.let { "/pass_md5/${it.groupValues[1]}" }

        if (md5 != null) {
            val baseUrl = "https://myvidplay.com$md5"
            val videoBase = app.get(baseUrl, referer = url).text.trim()
            val token = Regex("""token=([^&\s]+)""").find(res)?.groupValues?.get(1) ?: ""
            val finalUrl = "${videoBase}123456?token=$token&expiry=${System.currentTimeMillis()}"
            android.util.Log.d("DonghuaArena", "[MyVidPlay] finalUrl: $finalUrl")
            callback(
                newExtractorLink(name, name, finalUrl, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "https://myvidplay.com/"
                }
            )
            return
        }

        // Fallback: cari m3u8 atau mp4 langsung
        val direct = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(res)?.groupValues?.get(1)
        if (direct != null) {
            android.util.Log.d("DonghuaArena", "[MyVidPlay] direct: $direct")
            callback(
                newExtractorLink(
                    name, name, direct,
                    if (direct.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = url
                }
            )
        }
    }
}

// ============================================================
// LuluVdo / LuluVid — pakai jwplayer atau sources JSON
// ============================================================
abstract class LuluBase : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        android.util.Log.d("DonghuaArena", "[$name] getUrl => $url")
        val res = app.get(url, referer = referer).text

        // jwplayer sources array: sources:[{file:"..."}]
        val sources = Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["']([^"']+)["']""")
            .findAll(res).map { it.groupValues[1] }.toList()

        if (sources.isNotEmpty()) {
            sources.forEach { src ->
                android.util.Log.d("DonghuaArena", "[$name] source: $src")
                callback(
                    newExtractorLink(
                        name, name, src,
                        if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = url
                    }
                )
            }
            return
        }

        // Fallback: setup({"sources":[{"file":"..."}]})
        val setup = Regex("""setup\s*\(\s*\{.*?"file"\s*:\s*"([^"]+)""", RegexOption.DOT_MATCHES_ALL)
            .find(res)?.groupValues?.get(1)

        if (setup != null) {
            android.util.Log.d("DonghuaArena", "[$name] setup source: $setup")
            callback(
                newExtractorLink(
                    name, name, setup,
                    if (setup.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = url
                }
            )
            return
        }

        // Fallback m3u8/mp4 langsung
        val direct = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(res)?.groupValues?.get(1)
        if (direct != null) {
            android.util.Log.d("DonghuaArena", "[$name] direct: $direct")
            callback(
                newExtractorLink(
                    name, name, direct,
                    if (direct.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = url
                }
            )
        } else {
            android.util.Log.w("DonghuaArena", "[$name] TIDAK ditemukan di: $url")
        }
    }
}

class LuluVdo : LuluBase() {
    override val name = "LuluVdo"
    override val mainUrl = "https://luluvdo.com"
}

class LuluVid : LuluBase() {
    override val name = "LuluVid"
    override val mainUrl = "https://luluvid.com"
}

// ============================================================
// Byse — sama seperti LuluBase (jwplayer)
// ============================================================
class Byse : LuluBase() {
    override val name = "Byse"
    override val mainUrl = "https://bysezejataos.com"
}

// ============================================================
// Generic SimpleUniversalExtractor — untuk host lainnya
// ============================================================
abstract class SimpleUniversalExtractor : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        android.util.Log.d("DonghuaArena", "[$name] getUrl => $url")
        val res = try {
            app.get(url, referer = referer).text
        } catch (e: Exception) {
            android.util.Log.e("DonghuaArena", "[$name] Gagal fetch: ${e.message}")
            return
        }

        val mediaUrl = Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["']([^"']+)["']""").find(res)?.groupValues?.get(1)
            ?: Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(res)?.groupValues?.get(1)
            ?: Regex("""src\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(res)?.groupValues?.get(1)
            ?: Regex("""["'](https?://[^"']+\.m3u8[^"'\s]*)["']""").find(res)?.groupValues?.get(1)
            ?: Regex("""file\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(res)?.groupValues?.get(1)
            ?: Regex("""["'](https?://[^"']+\.mp4[^"'\s]*)["']""").find(res)?.groupValues?.get(1)

        android.util.Log.d("DonghuaArena", "[$name] mediaUrl: $mediaUrl")

        if (mediaUrl != null) {
            callback(
                newExtractorLink(
                    name, name, mediaUrl,
                    if (mediaUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = url
                }
            )
        } else {
            android.util.Log.w("DonghuaArena", "[$name] mediaUrl TIDAK ditemukan di: $url")
        }
    }
}

class StreamHls : SimpleUniversalExtractor() {
    override val name = "StreamHLS"
    override val mainUrl = "https://streamhls.to"
}

class MyVidPlaySimple : SimpleUniversalExtractor() {
    override val name = "MyVidPlaySimple"
    override val mainUrl = "https://myvidplay.com"
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

class DoodStream : SimpleUniversalExtractor() {
    override val name = "DoodStream"
    override val mainUrl = "https://doodstream.com"
}

// ============================================================
// ArchiveOrg & DTube — sama seperti sebelumnya
// ============================================================
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
        val doc = try { app.get(url).document } catch (e: Exception) { return }

        doc.select("a[href*='/download/'], source[src*='/download/']").forEach {
            val href = it.attr("href").ifBlank { it.attr("src") }
            val mediaUrl = if (href.startsWith("http")) href else "https://archive.org$href"
            callback(
                newExtractorLink(
                    name, name, mediaUrl,
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
        return "${hex.substring(0,8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20)}"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val rawId = if (url.contains("?v=")) url.substringAfter("?v=").substringBefore("&")
                    else url.substringAfterLast("/")
        if (rawId.isBlank()) return
        val videoId = if (rawId.contains("-")) rawId else base58ToUuid(rawId)
        val videoUrl = "https://nas1.d.tube/videos/$videoId/master.m3u8"
        callback(
            newExtractorLink(name, name, videoUrl, ExtractorLinkType.M3U8) {
                this.quality = Qualities.Unknown.value
                this.referer = url
            }
        )
    }
}
