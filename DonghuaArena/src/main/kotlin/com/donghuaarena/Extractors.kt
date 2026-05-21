package com.donghuaarena

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getAndUnpack

abstract class SimpleUniversalExtractor : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = try {
            app.get(url, referer = referer).text
        } catch (e: Exception) {
            return
        }

        val unpacked = getAndUnpack(res)
        val searchIn = if (unpacked.isNullOrBlank()) res else unpacked

        val mediaUrls = mutableListOf<String>()

        // Match common patterns in JS
        Regex("file\\s*[:=]\\s*[\"'](.*?)[\"']").findAll(searchIn).forEach { mediaUrls.add(it.groupValues[1]) }
        Regex("src\\s*[:=]\\s*[\"'](.*?)[\"']").findAll(searchIn).forEach { mediaUrls.add(it.groupValues[1]) }

        // MixDrop/DTube specific
        Regex("wurl\\s*[:=]\\s*[\"'](.*?)[\"']").findAll(searchIn).forEach { mediaUrls.add(it.groupValues[1]) }
        Regex("vfile\\s*[:=]\\s*[\"'](.*?)[\"']").findAll(searchIn).forEach { mediaUrls.add(it.groupValues[1]) }

        // Direct link patterns
        Regex("https?://[a-zA-Z0-9./_-]+\\.m3u8[a-zA-Z0-9./?=&_-]*").findAll(searchIn).forEach { mediaUrls.add(it.value) }
        Regex("https?://[a-zA-Z0-9./_-]+\\.mp4[a-zA-Z0-9./?=&_-]*").findAll(searchIn).forEach { mediaUrls.add(it.value) }

        for (link in mediaUrls.distinct()) {
            if (link.isBlank() || link.length < 10) continue
            if (link.contains("google.com") || link.contains("facebook.com") || link.contains("adnetwork") || link.contains("doubleclick")) continue

            val finalUrl = when {
                link.startsWith("http") -> link
                link.startsWith("//") -> "https:$link"
                link.startsWith("/") -> {
                    val base = url.substringBefore("/", "https://" + url.substringAfter("://").substringBefore("/"))
                    base + link
                }
                else -> url.substringBeforeLast("/") + "/" + link
            }

            callback(
                newExtractorLink(
                    name,
                    name,
                    finalUrl,
                    if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = url
                }
            )
        }
    }
}

class StreamHls : SimpleUniversalExtractor() {
    override val name = "StreamHLS"
    override val mainUrl = "https://streamhls.to"
}

class LuluVdo : SimpleUniversalExtractor() {
    override val name = "LuluVdo"
    override val mainUrl = "https://luluvdo.com"
}

class LuluVid : SimpleUniversalExtractor() {
    override val name = "LuluVid"
    override val mainUrl = "https://luluvid.com"
}

class LuluStream : SimpleUniversalExtractor() {
    override val name = "LuluStream"
    override val mainUrl = "https://lulustream.com"
}

class MyVidPlay : SimpleUniversalExtractor() {
    override val name = "MyVidPlay"
    override val mainUrl = "https://myvidplay.com"
}

class Byse : SimpleUniversalExtractor() {
    override val name = "Byse"
    override val mainUrl = "https://bysezejataos.com"
}

class TurboVid : SimpleUniversalExtractor() {
    override val name = "TurboVid"
    override val mainUrl = "https://turbovidhls.com"
}

class Vidara : SimpleUniversalExtractor() {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.so"
}

class Playmogo : SimpleUniversalExtractor() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
}

class StreamRuby : SimpleUniversalExtractor() {
    override val name = "StreamRuby"
    override val mainUrl = "https://streamruby.com"
}

class StreamRubyNet : SimpleUniversalExtractor() {
    override val name = "StreamRuby"
    override val mainUrl = "https://streamruby.net"
}

class StreamRubyHub : SimpleUniversalExtractor() {
    override val name = "StreamRuby"
    override val mainUrl = "https://rubyvidhub.com"
}

class MixDrop : SimpleUniversalExtractor() {
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.top"
}

class MixDropClick : SimpleUniversalExtractor() {
    override val name = "MixDrop"
    override val mainUrl = "https://m1xdrop.click"
}

class StreamTape : SimpleUniversalExtractor() {
    override val name = "StreamTape"
    override val mainUrl = "https://streamtape.com"
}

class ArchiveOrg : ExtractorApi() {
    override val mainUrl = "https://archive.org"
    override val requiresReferer = false
    override val name = "Internet Archive"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = try { app.get(url) } catch (e: Exception) { return }
        val res = response.text
        val doc = response.document

        for (it in doc.select("a[href*=\"/download/\"]")) {
            val href = it.attr("href")
            val mediaUrl = if (href.startsWith("http")) href else "https://archive.org" + href
            handleMedia(mediaUrl, it.text(), url, callback)
        }

        for (it in doc.select("source[src*=\"/download/\"]")) {
            val src = it.attr("src")
            val mediaUrl = if (src.startsWith("http")) src else "https://archive.org" + src
            handleMedia(mediaUrl, name, url, callback)
        }

        val playlist = doc.select("play-av").attr("playlist")
        if (playlist.isNotBlank()) {
            for (match in Regex("\"file\":\"(.*?)\"").findAll(playlist)) {
                val file = match.groupValues[1]
                val mediaUrl = if (file.startsWith("http")) file else "https://archive.org" + file
                handleMedia(mediaUrl, name, url, callback)
            }
        }

        for (match in Regex("\"file\":\"(.*?)\"").findAll(res)) {
            val file = match.groupValues[1]
            if (file.contains("/download/")) {
                val mediaUrl = if (file.startsWith("http")) file else "https://archive.org" + file
                handleMedia(mediaUrl, name, url, callback)
            }
        }
    }

    private suspend fun handleMedia(mediaUrl: String, label: String, referer: String, callback: (ExtractorLink) -> Unit) {
        if (mediaUrl.endsWith(".mp4") || mediaUrl.endsWith(".mkv") || mediaUrl.endsWith(".m3u8")) {
            val quality = if (mediaUrl.contains("1080")) Qualities.P1080.value
                         else if (mediaUrl.contains("720")) Qualities.P720.value
                         else Qualities.Unknown.value

            callback(
                newExtractorLink(
                    name,
                    label.ifBlank { name },
                    mediaUrl,
                    if (mediaUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.referer = referer
                }
            )
        }
    }
}

class DTube : ExtractorApi() {
    override var mainUrl = "https://play.d.tube"
    override val requiresReferer = true
    override var name = "DTube"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val rawId = if (url.contains("?v=")) {
            url.substringAfter("?v=").substringBefore("&")
        } else {
            url.substringAfterLast("/")
        }
        if (rawId.isBlank()) return

        val videoUrl = "https://nas1.d.tube/videos/$rawId/master.m3u8"
        callback(
            newExtractorLink(
                name,
                name,
                videoUrl,
                ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = url
            }
        )
    }
}
