package com.donghuaarena

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.api.Log
import com.lagradost.cloudstream3.extractors.DoodLaExtractor

open class SimpleUniversalExtractor : ExtractorApi() {
    override val name: String get() = "SimpleUniversal"
    override val mainUrl: String get() = ""
    override val requiresReferer: Boolean get() = true

    companion object {
        private const val TAG = "SimpleUniversalExt"
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        Log.d(TAG, "getUrl: $url referer: $referer")
        val response = app.get(url, referer = referer, headers = mapOf("User-Agent" to USER_AGENT)).text

        // Try to find m3u8 in scripts
        val m3u8Regex = """["'](https?://[^"']+\.m3u8[^"']*)["']""".toRegex()
        val m3u8Match = m3u8Regex.find(response)

        if (m3u8Match != null) {
            val link = m3u8Match.groupValues[1].replace("\\/", "/")
            Log.d(TAG, "Found m3u8: $link")
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    link,
                    INFER_TYPE
                ) {
                    this.referer = referer ?: url
                    // Leave quality unset for M3U8 to allow auto-detection of resolution tracks
                }
            )
        } else {
            // Try packed
            val unpacked = getAndUnpack(response)
            if (unpacked != response) {
                val packedMatch = m3u8Regex.find(unpacked)
                if (packedMatch != null) {
                    val link = packedMatch.groupValues[1].replace("\\/", "/")
                    Log.d(TAG, "Found m3u8 in packed: $link")
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
                Log.d(TAG, "Found video src: $link")
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

class StreamHls : SimpleUniversalExtractor() { override val name: String get() = "StreamHls"; override val mainUrl: String get() = "https://streamhls.my.id" }
class LuluVdo : SimpleUniversalExtractor() { override val name: String get() = "LuluVdo"; override val mainUrl: String get() = "https://luluvdo.com" }
class LuluVid : SimpleUniversalExtractor() { override val name: String get() = "LuluVid"; override val mainUrl: String get() = "https://luluvid.com" }
class LuluStream : SimpleUniversalExtractor() { override val name: String get() = "LuluStream"; override val mainUrl: String get() = "https://lulustream.com" }

class MyVidPlay : DoodLaExtractor() {
    override var name = "MyVidPlay"
    override var mainUrl = "https://myvidplay.com"
}

class Byse : SimpleUniversalExtractor() { override val name: String get() = "Byse"; override val mainUrl: String get() = "https://byse.site" }
class ByseSejataos : SimpleUniversalExtractor() { override val name: String get() = "Byse"; override val mainUrl: String get() = "https://bysezejataos.com" }
class TurboVid : SimpleUniversalExtractor() { override val name: String get() = "TurboVid"; override val mainUrl: String get() = "https://turbovid.eu" }
class Vidara : SimpleUniversalExtractor() { override val name: String get() = "Vidara"; override val mainUrl = "https://vidara.xyz" }
class Playmogo : SimpleUniversalExtractor() { override val name: String get() = "Playmogo"; override val mainUrl = "https://playmogo.com" }

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
