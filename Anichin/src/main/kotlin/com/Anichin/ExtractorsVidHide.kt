package com.Anichin

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class VidHide : ExtractorApi() {
    override val name = "VidHide"
    override val mainUrl = "https://minochinos.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("VidHide", "getUrl: $url, referer: $referer")
            val response = app.get(url, referer = referer ?: "https://anichin.moe/")
            val html = response.text
            Log.d("VidHide", "Page size: ${html.length}")
            Log.d("VidHide", "Page contains 'm3u8': ${html.contains("m3u8")}")
            Log.d("VidHide", "Page contains 'eval': ${html.contains("eval")}")
            Log.d("VidHide", "Page contains 'packed': ${html.contains("packed", ignoreCase = true)}")
            Log.d("VidHide", "Page contains 'function(p,a,c,k,e,d)': ${html.contains("function(p,a,c,k,e,d)")}")

            val m3u8Regex = Regex("""https?://[^\s"'<>]*\.m3u8[^\s"'<>]*""")
            val directM3u8 = m3u8Regex.find(html)
            if (directM3u8 != null) {
                val m3u8Url = directM3u8.value
                Log.d("VidHide", "Found direct m3u8: ${m3u8Url.take(80)}...")
                verifyAndReturn(m3u8Url, callback)
                Log.d("VidHide", "getUrl done (direct m3u8)")
                return
            }
            Log.d("VidHide", "No direct m3u8, looking for packed JS...")

            Log.d("VidHide", "No direct m3u8, scanning scripts for packed JS...")
            val allScripts = Jsoup.parse(html).select("script")
            Log.d("VidHide", "Total scripts: ${allScripts.size}")

            var found = false
            for (script in allScripts) {
                val data = script.data()
                if (data.contains("function(p,a,c,k,e,d)") || data.contains("eval(function(p,a,c,k,e,d)")) {
                    Log.d("VidHide", "Found packed JS script, length=${data.length}")
                    Log.d("VidHide", "Script content (first 500): ${data.take(500)}")

                    val startQuote = data.indexOfAny(charArrayOf('\'', '"'), data.indexOf("}("))
                    if (startQuote < 0) { Log.d("VidHide", "No start quote found"); continue }
                    val quoteChar = data[startQuote]
                    val pEnd = data.indexOf(quoteChar, startQuote + 1)
                    if (pEnd < 0) { Log.d("VidHide", "No end quote for p"); continue }
                    val p = data.substring(startQuote + 1, pEnd)

                    val rest = data.substring(pEnd + 1)
                    val parts = rest.split(",")
                    val a = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 36
                    val c = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0

                    val kStart = rest.indexOfAny(charArrayOf('\'', '"'), rest.indexOf("," + c + ","))
                    if (kStart < 0) { Log.d("VidHide", "No k quote start"); continue }
                    val kQuote = rest[kStart]
                    val kEnd = rest.indexOf(kQuote, kStart + 1)
                    if (kEnd < 0) { Log.d("VidHide", "No k quote end"); continue }
                    val kRaw = rest.substring(kStart + 1, kEnd)
                    val k = kRaw.split("|")

                    Log.d("VidHide", "Parsed: p.length=${p.length}, a=$a, c=$c, k.size=${k.size}")
                    Log.d("VidHide", "p first 200: ${p.take(200)}")

                    val unpacked = unpackPacker(p, a, c, k)
                    Log.d("VidHide", "Unpacked length: ${unpacked.length}")
                    Log.d("VidHide", "Unpacked contains m3u8: ${m3u8Regex.containsMatchIn(unpacked)}")
                    Log.d("VidHide", "Unpacked sample: ${unpacked.take(1000)}")

                    val m3u8InUnpacked = m3u8Regex.find(unpacked)
                    if (m3u8InUnpacked != null) {
                        val m3u8Url = m3u8InUnpacked.value
                        Log.d("VidHide", "Found m3u8: ${m3u8Url.take(80)}...")
                        verifyAndReturn(m3u8Url, callback)
                        Log.d("VidHide", "getUrl done (packed JS)")
                        found = true
                        return
                    } else {
                        Log.w("VidHide", "No m3u8 in unpacked JS")
                        Log.d("VidHide", "Unpacked bigger: ${unpacked.take(2000)}")
                    }
                }
            }
            if (!found) {
                Log.w("VidHide", "No packed JS found on page")
                allScripts.forEachIndexed { i, s ->
                    val d = s.data()
                    if (d.length > 20) Log.d("VidHide", "  script[$i]: ${d.take(200)}")
                }
                Log.d("VidHide", "HTML sample: ${html.take(2000)}")
            }
            Log.d("VidHide", "getUrl finished (no result)")
        } catch (e: Exception) {
            Log.w("VidHide", "UNCAUGHT: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun verifyAndReturn(m3u8Url: String, callback: (ExtractorLink) -> Unit) {
        Log.d("VidHide", "Verifying m3u8: ${m3u8Url.take(80)}...")
        val headers = mapOf(
            "Referer" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36",
        )
        try {
            val resp = app.get(m3u8Url, headers = headers)
            Log.d("VidHide", "m3u8 size: ${resp.text.length}")
            val body = resp.text
            if (body.startsWith("#EXTM3U")) {
                val variants = Regex("#EXT-X-STREAM-INF").findAll(body).count()
                val segments = Regex("#EXTINF").findAll(body).count()
                Log.d("VidHide", "Valid m3u8: $variants variants, $segments segments")
            } else {
                Log.w("VidHide", "NOT m3u8: ${body.take(300)}")
            }
        } catch (e: Exception) {
            Log.w("VidHide", "Verify FAILED: ${e.message}")
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
                this.headers = headers
            }
        )
    }

    private fun unpackPacker(p: String, a: Int, c: Int, k: List<String>): String {
        var result = p
        for (i in (c - 1) downTo 0) {
            val word = k.getOrNull(i) ?: continue
            if (word.isBlank()) continue
            val base36 = i.toString(a)
            result = result.replace(Regex("\\b$base36\\b"), word)
        }
        return result
    }
}
