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
                val base = m3u8Url.substringBeforeLast("/")

                val variants = mutableListOf<Pair<String, String>>()
                val streamInfRegex = Regex("#EXT-X-STREAM-INF[^#]*BANDWIDTH=(\\d+)[^#]*\n([^#\n]+)")
                streamInfRegex.findAll(body).forEach { m ->
                    val bw = m.groupValues[1].toIntOrNull() ?: 0
                    var varUrl = m.groupValues[2].trim()
                    if (!varUrl.startsWith("http")) {
                        varUrl = if (varUrl.startsWith("/")) {
                            "${m3u8Url.substringBefore("://")}://${m3u8Url.substringAfter("://").substringBefore("/")}$varUrl"
                        } else {
                            "$base/$varUrl"
                        }
                    }
                    val q = when {
                        bw >= 5000000 -> "1080p"
                        bw >= 2500000 -> "720p"
                        bw >= 1000000 -> "480p"
                        bw >= 500000  -> "360p"
                        else -> "240p"
                    }
                    Log.d("Turbovidhls", "  Variant: ${bw}bps($q) -> ${varUrl.take(80)}...")
                    variants.add(q to varUrl)
                }

                if (variants.isEmpty()) {
                    val segCount = Regex("#EXTINF").findAll(body).count()
                    Log.d("Turbovidhls", "No variants, segments: $segCount")
                    Log.d("Turbovidhls", "Body: ${body.take(1000)}")
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
                        this.headers = mHeaders
                    }
                )
            } else {
                Log.w("Turbovidhls", "Response is NOT m3u8: ${body.take(500)}")
            }
        } else {
            Log.w("Turbovidhls", "No data-hash found")
            Log.d("Turbovidhls", "Page sample: ${html.take(2000)}")
        }
    }
}
