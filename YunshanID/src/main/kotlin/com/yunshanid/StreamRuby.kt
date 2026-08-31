package com.yunshanid

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlin.text.Regex

open class StreamRuby : ExtractorApi() {
    override val name = "StreamRuby"
    override val mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val t0 = System.currentTimeMillis()
        Log.d("StreamRuby", "getUrl: $url")
        val id = "embed-([a-zA-Z0-9]+)\\.html".toRegex().find(url)?.groupValues?.get(1) ?: run {
            Log.w("StreamRuby", "No video ID found in URL")
            return
        }
        Log.d("StreamRuby", "Video ID: $id")

        val t1 = System.currentTimeMillis()
        Log.d("StreamRuby", "POST to /dl...")
        val response = app.post(
            "$mainUrl/dl", data = mapOf(
                "op" to "embed",
                "file_code" to id,
                "auto" to "1",
                "referer" to "",
            ), referer = referer
        )
        val t2 = System.currentTimeMillis()
        Log.d("StreamRuby", "POST done in ${t2 - t1}ms, response size: ${response.text.length}")

        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            Log.d("StreamRuby", "Has packed JS, unpacking...")
            getAndUnpack(response.text).also {
                Log.d("StreamRuby", "Unpack done in ${System.currentTimeMillis() - t2}ms")
            }
        } else {
            Log.d("StreamRuby", "No packed JS, looking for sources: script")
            response.document.selectFirst("script:containsData(sources:)")?.data()
        }
        if (script == null) {
            Log.w("StreamRuby", "No script found in response")
            Log.d("StreamRuby", "Response sample: ${response.text.take(1000)}")
            return
        }
        val m3u8 = Regex("file:\\s*\"(.*?m3u8.*?)\"").find(script)?.groupValues?.getOrNull(1)
        if (m3u8.isNullOrBlank()) {
            Log.w("StreamRuby", "No m3u8 URL in script")
            Log.d("StreamRuby", "Script sample: ${script.take(1000)}")
            return
        }
        Log.d("StreamRuby", "m3u8 found in ${System.currentTimeMillis() - t0}ms total: ${m3u8.take(80)}...")

        Log.d("StreamRuby", "Verifying m3u8...")
        try {
            val verifyResp = app.get(m3u8, headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36",
            ))
            Log.d("StreamRuby", "m3u8 size: ${verifyResp.text.length}")
            if (verifyResp.text.startsWith("#EXTM3U")) {
                val variants = Regex("#EXT-X-STREAM-INF").findAll(verifyResp.text).count()
                val segments = Regex("#EXTINF").findAll(verifyResp.text).count()
                Log.d("StreamRuby", "Valid m3u8: $variants variants, $segments segments")
            } else {
                Log.w("StreamRuby", "Response NOT m3u8: ${verifyResp.text.take(300)}")
            }
        } catch (e: Exception) {
            Log.w("StreamRuby", "m3u8 verify FAILED: ${e.message}")
        }

        callback.invoke(newExtractorLink(
            source = this.name,
            name = this.name,
            url = m3u8,
            type = ExtractorLinkType.M3U8,
            {
                quality = Qualities.Unknown.value
                this.referer = mainUrl
                this.headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36",
                )
            }
        ))
    }
}

class svanila : StreamRuby() {
    override var name = "svanila"
    override var mainUrl = "https://streamruby.net"
}

class svilla : StreamRuby() {
    override var name = "svilla"
    override var mainUrl = "https://streamruby.com"
}
