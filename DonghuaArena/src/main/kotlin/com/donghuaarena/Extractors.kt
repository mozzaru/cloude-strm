package com.donghuaarena

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.api.Log
import com.lagradost.cloudstream3.extractors.DoodLaExtractor

open class SimpleUniversalExtractor(override val name: String, override val mainUrl: String) : ExtractorApi() {
    override val requiresReferer: Boolean get() = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
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
        } else {
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
            videoRegex.find(response)?.groupValues?.get(1)?.let { link ->
                Log.d("Universal", "Found video src: $link")
                callback.invoke(newExtractorLink(this.name, this.name, link, INFER_TYPE) {
                    this.referer = referer ?: url
                    if (!link.contains(".m3u8")) this.quality = Qualities.Unknown.value
                })
            }
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
        Log.d(TAG, "videoId: $videoId")

        val byseHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "$mainUrl/"),
            "Accept" to "text/html,application/xhtml+xml,*/*"
        )

        val byseHtml = try {
            app.get(url, headers = byseHeaders).text
        } catch (e: Exception) {
            Log.e(TAG, "Gagal fetch Byse page: ${e.message}")
            return
        }

        val iframeRegex = """<iframe[^>]+src=["'](https?://[^"']+)["']""".toRegex()
        val iframeUrl = iframeRegex.findAll(byseHtml)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains(IFRAME_HOST) }

        if (iframeUrl == null) {
            Log.e(TAG, "Iframe $IFRAME_HOST tidak ditemukan di halaman Byse")
            Log.d(TAG, "HTML snippet: ${byseHtml.take(500).replace("\n", " ")}")
            return
        }
        Log.d(TAG, "Iframe URL ditemukan: $iframeUrl")

        val iframeOrigin = "https://$IFRAME_HOST"
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

        Log.d(TAG, "Iframe HTML snippet: ${iframeHtml.take(600).replace("\n", " ")}")

        val m3u8Regex = """["'`](https?://[^"'`\s]+\.m3u8[^"'`\s]*)["'`]""".toRegex()

        val directMatch = m3u8Regex.find(iframeHtml)
        if (directMatch != null) {
            val link = directMatch.groupValues[1].replace("\\/", "/")
            Log.d(TAG, "m3u8 dari iframe HTML: $link")
            callback.invoke(newExtractorLink(name, name, link, INFER_TYPE) {
                this.referer = iframeOrigin + "/"
            })
            return
        }

        val unpacked = getAndUnpack(iframeHtml)
        if (unpacked != iframeHtml) {
            Log.d(TAG, "Unpacked snippet: ${unpacked.take(400).replace("\n", " ")}")
            m3u8Regex.find(unpacked)?.let {
                val link = it.groupValues[1].replace("\\/", "/")
                Log.d(TAG, "m3u8 dari packed JS: $link")
                callback.invoke(newExtractorLink(name, name, link, INFER_TYPE) {
                    this.referer = iframeOrigin + "/"
                })
                return
            }
        }

        val jsonFieldRegex = """"(?:file|src|url|hls|source|stream)"\s*:\s*["'](https?://[^"']+)["']""".toRegex()
        jsonFieldRegex.find(iframeHtml)?.let {
            val link = it.groupValues[1].replace("\\/", "/")
            Log.d(TAG, "Link dari JSON field: $link")
            callback.invoke(newExtractorLink(name, name, link, INFER_TYPE) {
                this.referer = iframeOrigin + "/"
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

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        callback.invoke(newExtractorLink(this.name, this.name, url, INFER_TYPE) {
            this.quality = Qualities.Unknown.value
        })
    }
}

class DTube : ExtractorApi() {
    override val name: String get() = "DTube"
    override val mainUrl: String get() = "https://play.d.tube"
    override val requiresReferer: Boolean get() = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val videoId = url.split("?v=").lastOrNull()?.split("&")?.firstOrNull()
        if (videoId != null) {
            val m3u8 = "https://nas1.d.tube/videos/$videoId/master.m3u8"
            callback.invoke(newExtractorLink(this.name, this.name, m3u8, INFER_TYPE) {
                this.quality = Qualities.Unknown.value
            })
        }
    }
}
