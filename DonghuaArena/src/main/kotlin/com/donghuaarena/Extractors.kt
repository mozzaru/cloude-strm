package com.donghuaarena

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.api.Log
import com.lagradost.cloudstream3.extractors.DoodLaExtractor

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

class Byse : ExtractorApi() {
    override val name: String = "Byse"
    override val mainUrl: String = "https://bysezejataos.com"
    override val requiresReferer: Boolean = true

    companion object {
        private const val TAG = "Byse"
        private const val IFRAME_HOST = "rupertisdivingintoocean.com"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "getUrl: $url")

        val videoId = url.trimEnd('/').substringAfterLast("/")
        if (videoId.isBlank()) {
            Log.e(TAG, "Gagal ekstrak videoId dari: $url")
            return
        }
        Log.d(TAG, "videoId: $videoId")

        val iframeUrl = "https://$IFRAME_HOST/69p/$videoId"
        val iframeOrigin = "https://$IFRAME_HOST"
        Log.d(TAG, "Fetch iframe: $iframeUrl")

        val iframeHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Accept" to "text/html,application/xhtml+xml,*/*",
            "Origin" to mainUrl
        )

        val iframeHtml = try {
            app.get(iframeUrl, headers = iframeHeaders).text
        } catch (e: Exception) {
            Log.e(TAG, "Gagal fetch iframe: ${e.message}")
            return
        }

        Log.d(TAG, "Iframe snippet: ${iframeHtml.take(500).replace("\n", " ")}")

        val m3u8Regex = """["'`](https?://[^"'`\s]+\.m3u8[^"'`\s]*)["'`]""".toRegex()

        // 1. Cari m3u8 langsung di HTML
        val directMatch = m3u8Regex.find(iframeHtml)
        if (directMatch != null) {
            val link = directMatch.groupValues[1].replace("\\/", "/")
            Log.d(TAG, "m3u8 dari iframe HTML: $link")
            callback.invoke(newExtractorLink(name, name, link, INFER_TYPE) {
                this.referer = "$iframeOrigin/"
            })
            return
        }

        // 2. Coba unpack obfuscated JS
        val unpacked = getAndUnpack(iframeHtml)
        if (unpacked != iframeHtml) {
            Log.d(TAG, "Unpacked snippet: ${unpacked.take(400).replace("\n", " ")}")
            val packedMatch = m3u8Regex.find(unpacked)
            if (packedMatch != null) {
                val link = packedMatch.groupValues[1].replace("\\/", "/")
                Log.d(TAG, "m3u8 dari packed JS: $link")
                callback.invoke(newExtractorLink(name, name, link, INFER_TYPE) {
                    this.referer = "$iframeOrigin/"
                })
                return
            }
        }

        // 3. Fallback: JSON field "file","src","url","hls","source","stream"
        val jsonFieldRegex = """"(?:file|src|url|hls|source|stream)"\s*:\s*["'](https?://[^"']+)["']""".toRegex()
        val jsonMatch = jsonFieldRegex.find(iframeHtml)
        if (jsonMatch != null) {
            val link = jsonMatch.groupValues[1].replace("\\/", "/")
            Log.d(TAG, "Link dari JSON field: $link")
            callback.invoke(newExtractorLink(name, name, link, INFER_TYPE) {
                this.referer = "$iframeOrigin/"
            })
            return
        }

        Log.e(TAG, "m3u8 tidak ditemukan di iframe player")
    }
}

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
