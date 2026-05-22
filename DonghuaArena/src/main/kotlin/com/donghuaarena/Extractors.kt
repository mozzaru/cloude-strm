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

        // Try to find m3u8 in scripts
        val m3u8Regex = """["'](https?://[^"']+\.m3u8[^"']*)["']""".toRegex()
        val m3u8Match = m3u8Regex.find(response)

        if (m3u8Match != null) {
            val link = m3u8Match.groupValues[1].replace("\\/", "/")
            Log.d("Universal", "Found m3u8: $link")
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    link,
                    INFER_TYPE
                ) {
                    this.referer = referer ?: url
                }
            )
        } else {
            // Try packed
            val unpacked = getAndUnpack(response)
            if (unpacked != response) {
                val packedMatch = m3u8Regex.find(unpacked)
                if (packedMatch != null) {
                    val link = packedMatch.groupValues[1].replace("\\/", "/")
                    Log.d("Universal", "Found m3u8 in packed: $link")
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            link,
                            INFER_TYPE
                        ) {
                            this.referer = referer ?: url
                        }
                    )
                    return
                }
            }

            // Try generic video tag
            val videoRegex = """<video[^>]+src=["'](https?://[^"']+)["']""".toRegex()
            videoRegex.find(response)?.groupValues?.get(1)?.let { link ->
                Log.d("Universal", "Found video src: $link")
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        link,
                        INFER_TYPE
                    ) {
                        this.referer = referer ?: url
                        if (!link.contains(".m3u8")) {
                            this.quality = Qualities.Unknown.value
                        }
                    }
                )
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

// ─── Byse Extractor (Fixed) ──────────────────────────────────────────────────
// Problem: bysezejataos.com is a React SPA protected by Cloudflare Turnstile.
// The initial HTML never contains video URLs — the player loads them via a
// JSON API call after the challenge is solved.
// Fix: hit the known Byse JSON source endpoint directly using the embed ID,
// which is accessible without JS execution.
class Byse : ExtractorApi() {
    override val name: String = "Byse"
    override val mainUrl: String = "https://bysezejataos.com"
    override val requiresReferer: Boolean = true

    // Byse embed URLs follow the pattern: /e/{videoId}
    // Their backend exposes video sources at: /api/source/{videoId}
    // (common pattern for Plyr/HLS-based hosts like this one)
    private val sourceApiPath = "/api/source"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("Byse", "getUrl: $url referer: $referer")

        // Extract video ID from URL (e.g. https://bysezejataos.com/e/19ubis624q4z → 19ubis624q4z)
        val videoId = url.trimEnd('/').substringAfterLast("/")
        if (videoId.isBlank()) {
            Log.e("Byse", "Could not extract video ID from: $url")
            return
        }

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "$mainUrl/"),
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01"
        )

        // ── Attempt 1: /api/source/{id} (POST, common for this host family) ──
        try {
            val apiUrl = "$mainUrl$sourceApiPath/$videoId"
            Log.d("Byse", "Trying API POST: $apiUrl")
            val res = app.post(
                apiUrl,
                headers = headers,
                data = mapOf("r" to (referer ?: ""), "d" to mainUrl.removePrefix("https://"))
            ).text
            Log.d("Byse", "API response: $res")
            val m3u8 = parseSourceResponse(res, url, referer, callback)
            if (m3u8) return
        } catch (e: Exception) {
            Log.e("Byse", "API POST failed: ${e.message}")
        }

        // ── Attempt 2: /api/source/{id} (GET) ──
        try {
            val apiUrl = "$mainUrl$sourceApiPath/$videoId"
            Log.d("Byse", "Trying API GET: $apiUrl")
            val res = app.get(apiUrl, headers = headers).text
            Log.d("Byse", "API GET response: $res")
            val m3u8 = parseSourceResponse(res, url, referer, callback)
            if (m3u8) return
        } catch (e: Exception) {
            Log.e("Byse", "API GET failed: ${e.message}")
        }

        // ── Attempt 3: Regex scrape on the raw embed page ──
        // In case Cloudflare is bypassed by the request headers, scan HTML/JS
        try {
            Log.d("Byse", "Falling back to page scrape")
            val pageText = app.get(url, headers = headers).text
            val m3u8Regex = """["'`](https?://[^"'`\s]+\.m3u8[^"'`\s]*)["'`]""".toRegex()
            val match = m3u8Regex.find(pageText)
            if (match != null) {
                val link = match.groupValues[1].replace("\\/", "/")
                Log.d("Byse", "Scraped m3u8 from page: $link")
                callback.invoke(newExtractorLink(name, name, link, INFER_TYPE) {
                    this.referer = referer ?: url
                })
                return
            }

            // Also try unpacking obfuscated JS
            val unpacked = getAndUnpack(pageText)
            if (unpacked != pageText) {
                val packedMatch = m3u8Regex.find(unpacked)
                if (packedMatch != null) {
                    val link = packedMatch.groupValues[1].replace("\\/", "/")
                    Log.d("Byse", "Scraped m3u8 from packed JS: $link")
                    callback.invoke(newExtractorLink(name, name, link, INFER_TYPE) {
                        this.referer = referer ?: url
                    })
                    return
                }
            }
        } catch (e: Exception) {
            Log.e("Byse", "Page scrape failed: ${e.message}")
        }

        Log.e("Byse", "All attempts failed for: $url")
    }

    /**
     * Parses a JSON response that may look like:
     *   {"success":true,"data":[{"file":"https://...m3u8","label":"HD","type":"hls"}]}
     * or just a plain URL string.
     * Returns true if at least one link was found.
     */
    private suspend fun parseSourceResponse(
        response: String,
        originalUrl: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // Pattern: "file":"https://...m3u8"
        val fileRegex = """"file"\s*:\s*["'](https?://[^"']+)["']""".toRegex()
        fileRegex.findAll(response).forEach { match ->
            val link = match.groupValues[1].replace("\\/", "/")
            Log.d("Byse", "Found file in JSON: $link")
            callback.invoke(newExtractorLink(name, name, link, INFER_TYPE) {
                this.referer = referer ?: originalUrl
            })
            found = true
        }

        // Fallback: bare m3u8 URL anywhere in response
        if (!found) {
            val m3u8Regex = """https?://[^\s"']+\.m3u8[^\s"']*""".toRegex()
            m3u8Regex.find(response)?.let { match ->
                val link = match.value.replace("\\/", "/")
                Log.d("Byse", "Found bare m3u8 in response: $link")
                callback.invoke(newExtractorLink(name, name, link, INFER_TYPE) {
                    this.referer = referer ?: originalUrl
                })
                found = true
            }
        }

        return found
    }
}
// ─────────────────────────────────────────────────────────────────────────────

class MyVidPlay : DoodLaExtractor() {
    override var name: String = "DoodStream"
    override var mainUrl: String = "https://myvidplay.com"
}

class ArchiveOrg : ExtractorApi() {
    override val name: String get() = "Archive.org"
    override val mainUrl: String get() = "https://archive.org"
    override val requiresReferer: Boolean get() = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                url,
                INFER_TYPE
            ) {
                this.quality = Qualities.Unknown.value
            }
        )
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
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    m3u8,
                    INFER_TYPE
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
