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
        private const val PLAYER_HOST = "rupertisdivingintoocean.com"
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

        val playerOrigin = "https://$PLAYER_HOST"
        val iframeUrl = "$playerOrigin/69p/$videoId"

        // Step 1: Fetch iframe untuk dapat cookies (byse_viewer_id, byse_device_id)
        val iframeHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "id-ID,id;q=0.9",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl
        )

        val iframeResp = try {
            app.get(iframeUrl, headers = iframeHeaders)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal fetch iframe: ${e.message}")
            return
        }

        // Ambil cookies dari response
        val cookies = iframeResp.cookies
        Log.d(TAG, "Cookies dari iframe: $cookies")

        // Step 2: Hit API playback endpoint
        // Mode "embed" dengan X-Embed-Origin header
        val playbackUrl = "$playerOrigin/api/videos/${videoId}/embed/playback"

        val playbackHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$iframeUrl",
            "Accept" to "application/json, */*",
            "Accept-Language" to "id-ID,id;q=0.9",
            "Origin" to playerOrigin,
            "X-Embed-Origin" to mainUrl,
            "X-Embed-Referer" to "$mainUrl/",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin"
        )

        Log.d(TAG, "Fetching playback API: $playbackUrl")

        val playbackResp = try {
            app.get(
                playbackUrl,
                headers = playbackHeaders,
                cookies = cookies
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gagal fetch playback API: ${e.message}")
            return
        }

        Log.d(TAG, "Playback response code: ${playbackResp.code}")
        val body = playbackResp.text
        Log.d(TAG, "Playback response snippet: ${body.take(300)}")

        // Step 3: Parse response
        // Response terenkripsi: {playback: {key_parts:[...], iv:"...", payload:"..."}}
        // Atau langsung: {sources:[{url, mime_type, quality}]}

        // Coba parse sources langsung (unencrypted fallback)
        val directUrlRegex = """"url"\s*:\s*"(https?://[^"]+)"""".toRegex()
        val directMatch = directUrlRegex.findAll(body)
            .map { it.groupValues[1].replace("\\/", "/") }
            .firstOrNull { it.contains(".m3u8") || it.contains("hls") || it.contains("sprintcdn") }

        if (directMatch != null) {
            Log.d(TAG, "m3u8 dari playback API: $directMatch")
            callback.invoke(newExtractorLink(name, name, directMatch, INFER_TYPE) {
                this.referer = "$playerOrigin/"
            })
            return
        }

        // Response encrypted → log raw untuk debug
        Log.d(TAG, "Full playback response: ${body.take(1000)}")
        
        // Fallback: coba endpoint watch/playback (mungkin tidak encrypted)
        val watchPlaybackUrl = "$playerOrigin/api/videos/${videoId}/playback"
        val watchResp = try {
            app.get(watchPlaybackUrl, headers = playbackHeaders, cookies = cookies)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal fetch watch playback: ${e.message}")
            return
        }
        
        val watchBody = watchResp.text
        Log.d(TAG, "Watch playback snippet: ${watchBody.take(500)}")
        
        val watchMatch = directUrlRegex.findAll(watchBody)
            .map { it.groupValues[1].replace("\\/", "/") }
            .firstOrNull { it.contains(".m3u8") || it.contains("hls") }

        if (watchMatch != null) {
            Log.d(TAG, "m3u8 dari watch playback: $watchMatch")
            callback.invoke(newExtractorLink(name, name, watchMatch, INFER_TYPE) {
                this.referer = "$playerOrigin/"
            })
            return
        }

        Log.e(TAG, "Semua metode gagal. Response: ${watchBody.take(200)}")
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
