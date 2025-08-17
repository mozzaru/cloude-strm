package com.donghub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import android.util.Base64

class ArchiveExtractor : ExtractorApi() {
    override val name = "Archive"
    override val mainUrl = "https://archive.org"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim().replace(" ", "%20")
        
        try {
            // 1️⃣ Handle direct Archive.org URLs
            if (cleanUrl.contains("archive.org")) {
                handleArchiveUrl(cleanUrl, referer, callback)
                return
            }

            // 2️⃣ Handle Donghub episode pages
            if (cleanUrl.contains("donghub.vip")) {
                handleDonghubPage(cleanUrl, referer, subtitleCallback, callback)
                return
            }

            // 3️⃣ Generic URL handling
            handleGenericUrl(cleanUrl, referer, callback)

        } catch (e: Exception) {
            println("ArchiveExtractor error for $cleanUrl: ${e.message}")
        }
    }

    private suspend fun handleArchiveUrl(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
                "Referer" to (referer ?: mainUrl)
            )

            // Resolve redirect untuk mendapatkan direct streaming URL
            val response = app.get(url, headers = headers, allowRedirects = true)
            val finalUrl = response.url

            // Jika sudah resolve ke streaming server (ia*.archive.org)
            if (finalUrl.contains("ia\\d+\\.archive\\.org".toRegex()) || 
                finalUrl != url) {
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer ?: mainUrl
                        this.quality = getQualityFromName(finalUrl)
                        this.headers = headers
                    }
                )
            } else {
                // Jika masih download page, extract direct links
                extractFromArchivePage(response.document, referer, callback)
            }

        } catch (e: Exception) {
            println("Archive URL handling failed: ${e.message}")
        }
    }

    private suspend fun handleDonghubPage(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Referer" to (referer ?: "https://donghub.vip")
            )

            val response = app.get(url, headers = headers)
            val document = response.document

            // 1️⃣ Extract dari video player langsung
            document.select("#embed_holder video source, #embed_holder video").forEach { element ->
                val src = element.attr("src")
                if (src.isNotBlank()) {
                    val videoUrl = if (src.startsWith("http")) src else "https:$src"
                    createVideoLink(videoUrl, referer, callback, "Direct")
                }
            }

            // 2️⃣ Extract dari mirror options dengan base64 decode
            document.select(".mobius option, select.mirror option").forEach { option ->
                val base64Value = option.attr("value").trim()
                val serverName = option.text().trim()

                if (base64Value.isNotBlank() && base64Value != "" && serverName.isNotBlank()) {
                    extractFromBase64Option(base64Value, serverName, referer, callback)
                }
            }

            // 3️⃣ Extract dari JavaScript
            extractVideoFromScripts(document, referer, callback)

        } catch (e: Exception) {
            println("Donghub page handling failed: ${e.message}")
        }
    }

    private suspend fun extractFromBase64Option(
        base64Value: String,
        serverName: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Decode base64
            val decodedBytes = Base64.decode(base64Value, Base64.DEFAULT)
            val decodedHtml = String(decodedBytes, Charsets.UTF_8)
            
            // Parse decoded HTML
            val decodedDoc = Jsoup.parse(decodedHtml)
            
            // Extract video URLs
            decodedDoc.select("source, video").forEach { element ->
                val src = element.attr("src")
                if (src.isNotBlank()) {
                    val videoUrl = if (src.startsWith("http")) src else "https:$src"
                    createVideoLink(videoUrl, referer, callback, serverName)
                }
            }

            // Extract dari iframe jika ada
            decodedDoc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isNotBlank()) {
                    val iframeUrl = if (src.startsWith("http")) src else "https:$src"
                    
                    // Jika iframe Archive.org, extract langsung
                    if (iframeUrl.contains("archive.org")) {
                        createVideoLink(iframeUrl, referer, callback, serverName)
                    }
                }
            }

        } catch (e: Exception) {
            println("Base64 decode failed for '$serverName': ${e.message}")
            
            // Fallback: jika bukan base64, coba sebagai URL langsung
            if (base64Value.startsWith("http")) {
                createVideoLink(base64Value, referer, callback, serverName)
            }
        }
    }

    private suspend fun extractFromArchivePage(
        document: org.jsoup.nodes.Document,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        // Extract download links dari halaman archive.org
        document.select("a[href*='.mp4'], a[href*='.mkv'], a[href*='.avi']").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank()) {
                val videoUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                val filename = link.text().trim().ifBlank { "Video" }
                createVideoLink(videoUrl, referer, callback, filename)
            }
        }
    }

    private suspend fun extractVideoFromScripts(
        document: org.jsoup.nodes.Document,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        document.select("script").forEach { script ->
            val content = script.html()
            
            // Patterns untuk video URLs
            val patterns = listOf(
                // Archive.org specific
                """["']?(https://archive\.org/download/[^"'\s]+\.mp4[^"'\s]*)["']?""".toRegex(),
                """["']?(https://ia\d+\.archive\.org/[^"'\s]+\.mp4[^"'\s]*)["']?""".toRegex(),
                // General video URLs
                """src\s*[:=]\s*["']([^"']+\.mp4[^"']*)["']""".toRegex(),
                """url\s*[:=]\s*["']([^"']+\.mp4[^"']*)["']""".toRegex()
            )

            patterns.forEach { pattern ->
                pattern.findAll(content).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.isNotBlank() && videoUrl.startsWith("http")) {
                        createVideoLink(videoUrl, referer, callback, "Script")
                    }
                }
            }
        }
    }

    private suspend fun createVideoLink(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
        sourceName: String
    ) {
        val cleanUrl = url.trim().replace(" ", "%20")
        if (cleanUrl.isBlank()) return

        try {
            // Resolve Archive.org URLs ke streaming server
            val finalUrl = if (cleanUrl.contains("archive.org/download") && 
                              !cleanUrl.contains("ia\\d+\\.archive\\.org".toRegex())) {
                resolveArchiveStreamingUrl(cleanUrl)
            } else {
                cleanUrl
            }

            val quality = getQualityFromName(finalUrl)
            val linkType = if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$sourceName - $name",
                    url = finalUrl,
                    type = linkType
                ) {
                    this.referer = referer ?: mainUrl
                    this.quality = quality
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "*/*",
                        "Accept-Encoding" to "gzip, deflate, br"
                    )
                }
            )

        } catch (e: Exception) {
            println("Failed to create video link for $cleanUrl: ${e.message}")
        }
    }

    private suspend fun resolveArchiveStreamingUrl(url: String): String {
        return try {
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*"
            )
            
            val response = app.get(url, headers = headers, allowRedirects = true)
            
            // Jika redirect ke streaming server
            if (response.url.contains("ia\\d+\\.archive\\.org".toRegex())) {
                response.url
            } else {
                url
            }
        } catch (e: Exception) {
            println("Archive URL resolution failed: ${e.message}")
            url
        }
    }

    private suspend fun handleGenericUrl(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, allowRedirects = true)
            val finalUrl = response.url
            
            // Jika redirect ke file video langsung
            if (finalUrl.matches(".*\\.(mp4|mkv|avi|m4v)$".toRegex())) {
                createVideoLink(finalUrl, referer, callback, "Direct")
            }
        } catch (e: Exception) {
            println("Generic URL handling failed: ${e.message}")
        }
    }

    private fun getQualityFromName(name: String): Int {
        return when {
            name.contains("1080", true) -> Qualities.P1080.value
            name.contains("720", true) -> Qualities.P720.value
            name.contains("480", true) -> Qualities.P480.value
            name.contains("360", true) -> Qualities.P360.value
            name.contains("240", true) -> Qualities.P240.value
            name.contains("hd", true) -> Qualities.P720.value
            name.contains("sd", true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }
}

// Extractor khusus untuk Archive.org
class ArchiveOrgExtractor : ExtractorApi() {
    override val name = "ArchiveOrg"
    override val mainUrl = "https://archive.org"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Delegate ke ArchiveExtractor
        ArchiveExtractor().getUrl(url, referer, subtitleCallback, callback)
    }
}
