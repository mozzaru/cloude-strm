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
class ByseFallback : SimpleUniversalExtractor("Byse", "https://bysezejataos.com")

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
