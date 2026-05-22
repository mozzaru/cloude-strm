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

        // 1. Try to find m3u8 in scripts or main page
        val m3u8Regex = """["'](https?://[^"']+\.m3u8[^"']*)["']""".toRegex()
        var m3u8Match = m3u8Regex.find(response)

        if (m3u8Match == null) {
            // Try packed
            val unpacked = getAndUnpack(response)
            if (unpacked != response) {
                m3u8Match = m3u8Regex.find(unpacked)
            }
        }

        if (m3u8Match != null) {
            val link = m3u8Match.groupValues[1].replace("\\/", "/")
            Log.d("Universal", "Found m3u8 in page: $link")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = link,
                    type = INFER_TYPE
                ) {
                    this.referer = referer ?: url
                }
            )
            return
        }

        // 2. Try generic video tag
        val videoRegex = """<video[^>]+src=["'](https?://[^"']+)["']""".toRegex()
        videoRegex.find(response)?.groupValues?.get(1)?.let { link ->
            Log.d("Universal", "Found video src: $link")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = link,
                    type = INFER_TYPE
                ) {
                    this.referer = referer ?: url
                    if (!link.contains(".m3u8")) {
                        this.quality = Qualities.Unknown.value
                    }
                }
            )
            return
        }

        // 3. Fallback for SPAs: try to find any M3U8 in script tags
        val scriptSrcRegex = """<script[^>]+src=["']([^"']+\.js[^"']*)["']""".toRegex()
        val scripts = scriptSrcRegex.findAll(response).map { it.groupValues[1] }.toList()
        Log.d("Universal", "Scanning ${scripts.size} scripts for $url")
        for (scriptPath in scripts) {
            val scriptUrl = if (scriptPath.startsWith("http")) scriptPath else {
                if (scriptPath.startsWith("/")) {
                    val protocol = if (url.startsWith("https")) "https" else "http"
                    val domain = url.substringAfter("://").substringBefore("/")
                    "$protocol://$domain$scriptPath"
                } else {
                    val base = url.substringBeforeLast("/")
                    "$base/$scriptPath"
                }
            }
            try {
                Log.d("Universal", "Scanning JS: $scriptUrl")
                val jsContent = app.get(scriptUrl, referer = url).text
                val match = m3u8Regex.find(jsContent)
                if (match != null) {
                    val link = match.groupValues[1].replace("\\/", "/")
                    Log.d("Universal", "Found m3u8 in JS: $link")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = link,
                            type = INFER_TYPE
                        ) {
                            this.referer = url
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                // Ignore script fetch errors
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
class ByseUniversal : SimpleUniversalExtractor("Byse", "https://bysezejataos.com")
class ByseUniversal2 : SimpleUniversalExtractor("Byse", "https://byse.site")

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
                source = this.name,
                name = this.name,
                url = url,
                type = INFER_TYPE
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
                    source = this.name,
                    name = this.name,
                    url = m3u8,
                    type = INFER_TYPE
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
