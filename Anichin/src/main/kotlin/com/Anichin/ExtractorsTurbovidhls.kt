package com.Anichin

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class Turbovidhls : ExtractorApi() {
    override val name = "Turbovidhls"
    override val mainUrl = "https://turbovidhls.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Turbovidhls", "getUrl: $url, referer: $referer")
        val response = app.get(url, referer = referer)
        val html = response.text
        Log.d("Turbovidhls", "Page size: ${html.length}")

        val hashMatch = Regex("""data-hash="([^"]+)""").find(html)
        if (hashMatch != null) {
            val m3u8Url = hashMatch.groupValues[1]
            Log.d("Turbovidhls", "Found m3u8 URL: $m3u8Url")

            Log.d("Turbovidhls", "Verifying m3u8 accessibility...")
            val m3u8Response = try {
                app.get(m3u8Url, headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36",
                    "Origin" to mainUrl,
                ))
            } catch (e: Exception) {
                Log.w("Turbovidhls", "m3u8 fetch FAILED: ${e.message}")
                return
            }
            Log.d("Turbovidhls", "m3u8 size: ${m3u8Response.text.length}")
            val body = m3u8Response.text
            if (body.startsWith("#EXTM3U")) {
                Log.d("Turbovidhls", "Valid m3u8 header confirmed")
                val variantCount = Regex("#EXT-X-STREAM-INF").findAll(body).count()
                Log.d("Turbovidhls", "Variant count: $variantCount")
                if (variantCount > 0) {
                    Regex("#EXT-X-STREAM-INF[^#]+#EXTINF[^#]+\n([^#\n]+)")
                        .findAll(body).forEachIndexed { i, m ->
                            Log.d("Turbovidhls", "  Variant $i: ${m.groupValues[1]}")
                        }
                }
                val segmentCount = Regex("#EXTINF").findAll(body).count()
                Log.d("Turbovidhls", "Segment count: $segmentCount")
            } else {
                Log.w("Turbovidhls", "Response is NOT an m3u8 (no #EXTM3U header)")
                Log.d("Turbovidhls", "First 500 chars: ${body.take(500)}")
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36",
                        "Origin" to mainUrl,
                    )
                }
            )
        } else {
            Log.w("Turbovidhls", "No data-hash found in page HTML")
            Log.d("Turbovidhls", "Page sample: ${html.take(2000)}")
        }
    }
}
