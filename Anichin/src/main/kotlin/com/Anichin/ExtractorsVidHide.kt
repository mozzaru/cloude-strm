package com.Anichin

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.util.regex.Pattern

open class VidHide : ExtractorApi() {
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

            // Relative HLS paths (e.g. "/stream/...") must be resolved against the host that
            // actually served the embed — not the hardcoded mainUrl — so subclasses that point
            // at a different mirror (e.g. morencius.com) keep working.
            val embedBase = Regex("""^(https?://[^/]+)""").find(response.url)?.groupValues?.get(1)
                ?: mainUrl

            val decoded = decodePackedJs(html)
            if (decoded != null) {
                Log.d("VidHide", "Decoded JS size: ${decoded.length}")

                val hls4 = Regex("""["']hls4["']\s*:\s*["']([^"']+)["']""").find(decoded)
                val hls3 = Regex("""["']hls3["']\s*:\s*["']([^"']+)["']""").find(decoded)
                val hls2 = Regex("""["']hls2["']\s*:\s*["']([^"']+)["']""").find(decoded)

                val toTry = listOfNotNull(
                    hls4?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
                    hls3?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
                    hls2?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                ).map {
                    when {
                        it.startsWith("http") -> it
                        it.startsWith("//") -> "https:$it"
                        else -> "$embedBase$it"
                    }
                }

                Log.d("VidHide", "Found ${toTry.size} video URLs from decoded JS")
                for (videoUrl in toTry) {
                    Log.d("VidHide", "Trying: ${videoUrl.take(80)}...")
                    val success = verifyAndReturn(videoUrl, callback)
                    if (success) {
                        Log.d("VidHide", "SUCCESS with: ${videoUrl.take(80)}...")
                        return
                    }
                }

                Log.w("VidHide", "None of the decoded URLs worked")
                return
            }

            Log.d("VidHide", "No packed JS found, falling back to regex extraction")

            val m3u8UrlRegex = Regex("""https?://[^\s"'<>]*\.m3u8[^\s"'<>]*""")
            val directM3u8 = m3u8UrlRegex.find(html)
            if (directM3u8 != null) {
                Log.d("VidHide", "Found direct m3u8: ${directM3u8.value.take(80)}...")
                verifyAndReturn(directM3u8.value, callback)
                return
            }
            Log.d("VidHide", "No direct m3u8")

            val allScripts = Jsoup.parse(html).select("script")
            Log.d("VidHide", "Total scripts: ${allScripts.size}")

            val allJs = allScripts.joinToString("\n") { it.data() }

            val fileM3u8 = Regex("""file["'\s]*:["'\s]*([^"'\s,]+\.m3u8[^"'\s,]*)""").find(allJs)
            if (fileM3u8 != null) {
                val url = fileM3u8.groupValues[1].trim()
                Log.d("VidHide", "Found JW file m3u8: $url")
                verifyAndReturn(url, callback)
                return
            }

            val srcM3u8 = Regex("""src["'\s]*:["'\s]*([^"'\s,]+\.m3u8[^"'\s,]*)""").find(allJs)
            if (srcM3u8 != null) {
                val url = srcM3u8.groupValues[1].trim()
                Log.d("VidHide", "Found src m3u8: $url")
                verifyAndReturn(url, callback)
                return
            }

            val anyM3u8 = Regex("""[a-zA-Z0-9_\-./:?&=]+\.m3u8[a-zA-Z0-9_\-./:?&=]*""").find(allJs)
            if (anyM3u8 != null) {
                val url = anyM3u8.value
                val fullUrl = if (url.startsWith("http")) url else "https:$url"
                Log.d("VidHide", "Found any m3u8: ${fullUrl.take(80)}...")
                verifyAndReturn(fullUrl, callback)
                return
            }

            Log.w("VidHide", "No m3u8 found in any script")
        } catch (e: Exception) {
            Log.w("VidHide", "UNCAUGHT: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun decodePackedJs(html: String): String? {
        val evalMatch = Regex(
            """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)\s*\{"""
        ).find(html) ?: return null

        var depth = 1
        var idx = evalMatch.range.last + 1
        while (idx < html.length && depth > 0) {
            when (html[idx]) {
                '{' -> depth++
                '}' -> depth--
            }
            idx++
        }

        val parenIdx = html.indexOf('(', idx)
        if (parenIdx < 0) return null

        return parsePackedArgs(html, parenIdx + 1)
    }

    private fun parsePackedArgs(html: String, startIdx: Int): String? {
        var i = startIdx
        while (i < html.length && html[i] == ' ') i++
        if (i >= html.length || html[i] != '\'') return null
        i++

        val packed = StringBuilder()
        while (i < html.length) {
            if (html[i] == '\\' && i + 1 < html.length) {
                when (html[i + 1]) {
                    '\'' -> { packed.append('\''); i += 2 }
                    '\\' -> { packed.append('\\'); i += 2 }
                    else -> { packed.append(html[i]); i++ }
                }
            } else if (html[i] == '\'') {
                i++
                break
            } else {
                packed.append(html[i])
                i++
            }
        }

        while (i < html.length && html[i] == ' ') i++
        if (i >= html.length || html[i] != ',') return null
        i++

        while (i < html.length && html[i] == ' ') i++
        val aEnd = html.indexOf(',', i)
        if (aEnd < 0) return null
        val radix = html.substring(i, aEnd).trim().toIntOrNull() ?: return null
        i = aEnd + 1

        while (i < html.length && html[i] == ' ') i++
        val cEnd = html.indexOf(',', i)
        if (cEnd < 0) return null
        val count = html.substring(i, cEnd).trim().toIntOrNull() ?: return null
        i = cEnd + 1

        while (i < html.length && html[i] == ' ') i++
        if (i >= html.length || html[i] != '\'') return null
        i++

        val keysRaw = StringBuilder()
        while (i < html.length) {
            if (html[i] == '\\' && i + 1 < html.length && html[i + 1] == '\'') {
                keysRaw.append('\'')
                i += 2
            } else if (html[i] == '\'') {
                i++
                break
            } else {
                keysRaw.append(html[i])
                i++
            }
        }
        val keys = keysRaw.toString().split('|')

        return unpack(packed.toString(), radix, count, keys)
    }

    private fun unpack(packed: String, radix: Int, count: Int, keys: List<String>): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        fun toBaseX(n: Int): String {
            if (n == 0) return "0"
            var num = n
            val sb = StringBuilder()
            while (num > 0) {
                sb.insert(0, alphabet[num % radix])
                num /= radix
            }
            return sb.toString()
        }

        var result = packed
        for (i in count - 1 downTo 0) {
            if (i < keys.size && keys[i].isNotEmpty()) {
                val word = toBaseX(i)
                result = result.replace(
                    Regex("(?<![a-zA-Z0-9])" + Pattern.quote(word) + "(?![a-zA-Z0-9])"),
                    keys[i]
                )
            }
        }
        return result
    }

    private suspend fun verifyAndReturn(m3u8Url: String, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("VidHide", "Verifying m3u8: ${m3u8Url.take(80)}...")
        val headers = mapOf(
            "Referer" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36",
        )
        try {
            val resp = app.get(m3u8Url, headers = headers)
            if (resp.text.startsWith("#EXTM3U")) {
                val variants = Regex("#EXT-X-STREAM-INF").findAll(resp.text).count()
                val segments = Regex("#EXTINF").findAll(resp.text).count()
                Log.d("VidHide", "Valid m3u8: $variants variants, $segments segments")
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
                return true
            } else {
                Log.w("VidHide", "NOT m3u8: ${resp.text.take(300)}")
            }
        } catch (e: Exception) {
            Log.w("VidHide", "Verify FAILED: ${e.message}")
        }
        return false
    }
}

/**
 * The Vidhide server on anichin currently proxies through morencius.com (previously
 * minochinos.com). It serves the same JWPlayer packed-JS layout, so we reuse the VidHide
 * extractor logic but register it for the new host so loadExtractor() dispatches correctly.
 */
class Morencius : VidHide() {
    override val name = "VidHide"
    override val mainUrl = "https://morencius.com"
}
