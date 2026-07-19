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
        try {
            Log.d("Turbovidhls", "getUrl: $url, referer: $referer")
            val response = app.get(url, referer = referer)
            val html = response.text
            Log.d("Turbovidhls", "Page size: ${html.length}")

            val m3u8Url = extractM3u8FromPage(html)
            if (m3u8Url != null) {
                Log.d("Turbovidhls", "Found m3u8 URL: ${m3u8Url.take(80)}...")
                handleM3u8(m3u8Url, callback)
                return
            }

            Log.w("Turbovidhls", "No m3u8 found in page")
            Log.d("Turbovidhls", "Page sample: ${html.take(2000)}")
        } catch (e: Exception) {
            Log.w("Turbovidhls", "UNCAUGHT: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun extractM3u8FromPage(html: String): String? {
        val hashMatch = Regex("""data-hash="([^"]+)""").find(html)
        if (hashMatch != null) {
            val url = hashMatch.groupValues[1]
            Log.d("Turbovidhls", "Extracted via data-hash: ${url.take(60)}...")
            return url
        }

        val m3u8Direct = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(html)
        if (m3u8Direct != null) {
            Log.d("Turbovidhls", "Extracted via direct regex: ${m3u8Direct.value.take(60)}...")
            return m3u8Direct.value
        }

        val srcM3u8 = Regex("""src=["']([^"']+\.m3u8[^"']*)["']""").find(html)
        if (srcM3u8 != null) {
            val url = srcM3u8.groupValues[1]
            Log.d("Turbovidhls", "Extracted via src attr: $url")
            return if (url.startsWith("http")) url else "https:$url"
        }

        return null
    }

    private suspend fun handleM3u8(m3u8Url: String, callback: (ExtractorLink) -> Unit) {
        val mHeaders = mapOf(
            "Referer" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36",
            "Origin" to mainUrl,
        )

        Log.d("Turbovidhls", "Verifying m3u8 accessibility...")
        val m3u8Response = try {
            app.get(m3u8Url, headers = mHeaders)
        } catch (e: Exception) {
            Log.w("Turbovidhls", "m3u8 fetch FAILED: ${e.message}")
            return
        }
        val body = m3u8Response.text
        Log.d("Turbovidhls", "m3u8 size: ${body.length}")

        if (body.startsWith("#EXTM3U")) {
            Log.d("Turbovidhls", "Valid m3u8 header confirmed")
            val variantCount = Regex("#EXT-X-STREAM-INF").findAll(body).count()
            val segmentCount = Regex("#EXTINF").findAll(body).count()
            Log.d("Turbovidhls", "$variantCount variants, $segmentCount segments")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = mHeaders
                }
            )
        } else {
            Log.w("Turbovidhls", "Response is NOT m3u8: ${body.take(500)}")
        }
    }
}
