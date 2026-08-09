package com.Anichin

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Rumble", "getUrl: $url")
        val response = app.get(url, referer = referer ?: "$mainUrl/")
        val html = response.text

        // Current Rumble embed pages expose the playable sources in a JS object like:
        //   b.f["<vid>"]={...,"u":{...,"hls":{"url":"https://rumble.com/hls-vod/<id>/playlist.m3u8"}}}
        // The old "mp4":{...} JSON shape no longer exists, so we look for the HLS master
        // playlist directly.
        val hlsMatch = Regex("""\"hls\"\s*:\s*\{\"url\"\s*:\s*\"(https?:\\?/\\?/[^"]+playlist\.m3u8[^"]*)\"}""")
            .find(html)
            ?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.replace("\\u002F", "/")

        if (!hlsMatch.isNullOrBlank()) {
            Log.d("Rumble", "Found HLS master: $hlsMatch")
            verifyAndReturn(hlsMatch, callback)
            return
        }

        // Fallback: any rumble.com/hls-vod/.../playlist.m3u8 on the page.
        val fallback = Regex("""(https?:\\?/\\?/rumble\.com\\?/hls-vod\\?/[A-Za-z0-9_-]+\\?/playlist\.m3u8)""")
            .find(html)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.replace("\\u002F", "/")

        if (!fallback.isNullOrBlank()) {
            Log.d("Rumble", "Fallback HLS master: $fallback")
            verifyAndReturn(fallback, callback)
            return
        }

        Log.w("Rumble", "No HLS master playlist found in embed page")
    }

    private suspend fun verifyAndReturn(m3u8Url: String, callback: (ExtractorLink) -> Unit) {
        return try {
            val resp = app.get(m3u8Url, referer = "$mainUrl/")
            if (resp.text.startsWith("#EXTM3U")) {
                val variants = Regex("#EXT-X-STREAM-INF").findAll(resp.text).count()
                Log.d("Rumble", "Valid m3u8 ($variants variants)")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                Log.w("Rumble", "URL did not return an m3u8: ${resp.text.take(200)}")
            }
        } catch (e: Exception) {
            Log.w("Rumble", "m3u8 verify failed: ${e.message}")
        }
    }
}
