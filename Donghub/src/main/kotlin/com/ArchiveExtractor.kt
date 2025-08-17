package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

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
        
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept-Encoding" to "gzip, deflate, br",
            "DNT" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1"
        )

        try {
            // 1️⃣ Direct URL check - jika sudah format iaXXXX.archive.org
            if (cleanUrl.contains("ia\\d+\\.archive\\.org".toRegex())) {
                handleDirectArchiveUrl(cleanUrl, referer, callback)
                return
            }

            // 2️⃣ Download page URL - convert ke streaming URL
            if (cleanUrl.contains("/download/")) {
                handleDownloadUrl(cleanUrl, referer, callback, headers)
                return
            }

            // 3️⃣ Details page URL - extract dari halaman detail
            if (cleanUrl.contains("/details/")) {
                handleDetailsUrl(cleanUrl, referer, callback, headers)
                return
            }

            // 4️⃣ Embed URL
            if (cleanUrl.contains("/embed/")) {
                handleEmbedUrl(cleanUrl, referer, callback, headers)
                return
            }

            // 5️⃣ Generic URL handling
            handleGenericUrl(cleanUrl, referer, callback, headers)

        } catch (e: Exception) {
            println("ArchiveExtractor error for $cleanUrl: ${e.message}")
        }
    }

    private suspend fun handleDirectArchiveUrl(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        // Direct archive URLs biasanya sudah siap pakai
        addExtractorLink(url, referer, callback, "Direct")
    }

    private suspend fun handleDownloadUrl(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
        headers: Map<String, String>
    ) {
        try {
            // Convert download URL ke streaming format
            val response = app.get(url, headers = headers)
            val doc = response.document

            // Cari semua file video
            doc.select("a[href*='.mp4'], a[href*='.mkv'], a[href*='.avi'], a[href*='.m4v']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank()) {
                    val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                    val fileName = link.text().trim()
                    addExtractorLink(fullUrl, referer, callback, fileName.ifBlank { "Video" })
                }
            }

            // Cari dari JavaScript atau JSON data
            extractFromScripts(doc, referer, callback)

        } catch (e: Exception) {
            println("Download URL handling failed: ${e.message}")
        }
    }

    private suspend fun handleDetailsUrl(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
        headers: Map<String, String>
    ) {
        try {
            val response = app.get(url, headers = headers)
            val doc = response.document

            // Cari embed atau download links
            doc.select("a[href*='/download/']").forEach { downloadLink ->
                val downloadUrl = downloadLink.attr("href")
                if (downloadUrl.isNotBlank()) {
                    val fullDownloadUrl = if (downloadUrl.startsWith("http")) {
                        downloadUrl
                    } else {
                        "$mainUrl$downloadUrl"
                    }
                    handleDownloadUrl(fullDownloadUrl, referer, callback, headers)
                }
            }

            // Cari video player langsung
            doc.select("video source, video").forEach { video ->
                val src = video.attr("src")
                if (src.isNotBlank()) {
                    val fullUrl = if (src.startsWith("http")) src else "$mainUrl$src"
                    addExtractorLink(fullUrl, referer, callback, "Player")
                }
            }

            // Extract dari scripts
            extractFromScripts(doc, referer, callback)

        } catch (e: Exception) {
            println("Details URL handling failed: ${e.message}")
        }
    }

    private suspend fun handleEmbedUrl(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
        headers: Map<String, String>
    ) {
        try {
            val response = app.get(url, headers = headers)
            val doc = response.document

            // Cari video sources
            doc.select("video source, video").forEach { element ->
                val src = element.attr("src")
                if (src.isNotBlank()) {
                    val fullUrl = if (src.startsWith("http")) src else "$mainUrl$src"
                    addExtractorLink(fullUrl, referer, callback, "Embed")
                }
            }

            extractFromScripts(doc, referer, callback)

        } catch (e: Exception) {
            println("Embed URL handling failed: ${e.message}")
        }
    }

    private suspend fun handleGenericUrl(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
        headers: Map<String, String>
    ) {
        try {
            val response = app.get(url, headers = headers, allowRedirects = true)
            val finalUrl = response.url
            val doc = response.document

            // Jika redirect ke direct file
            if (finalUrl != url && finalUrl.matches(".*\\.(mp4|mkv|avi|m4v)$".toRegex())) {
                addExtractorLink(finalUrl, referer, callback, "Redirect")
                return
            }

            // Parse document untuk video sources
            doc.select("video source, video").forEach { element ->
                val src = element.attr("src")
                if (src.isNotBlank()) {
                    val fullUrl = if (src.startsWith("http")) src else "$mainUrl$src"
                    addExtractorLink(fullUrl, referer, callback, "Generic")
                }
            }

            extractFromScripts(doc, referer, callback)

        } catch (e: Exception) {
            println("Generic URL handling failed: ${e.message}")
        }
    }

    private suspend fun extractFromScripts(
        doc: org.jsoup.nodes.Document,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        doc.select("script").forEach { script ->
            val content = script.html()
            
            // Pattern untuk mencari URL video
            val patterns = listOf(
                // Archive.org streaming URLs
                """["']?(https?://ia\d+\.archive\.org/[^"'\s]+\.(mp4|mkv|avi|m4v)[^"'\s]*)["']?""".toRegex(),
                // General video URLs
                """["']?(https?://[^"'\s]+\.(mp4|mkv|avi|m4v)[^"'\s]*)["']?""".toRegex(),
                // Archive download URLs  
                """["']?(https?://archive\.org/download/[^"'\s]+\.(mp4|mkv|avi|m4v)[^"'\s]*)["']?""".toRegex(),
                // Serve URLs
                """["']?(https?://archive\.org/serve/[^"'\s]+\.(mp4|mkv|avi|m4v)[^"'\s]*)["']?""".toRegex()
            )

            patterns.forEach { pattern ->
                pattern.findAll(content).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.isNotBlank() && videoUrl.startsWith("http")) {
                        addExtractorLink(videoUrl, referer, callback, "Script")
                    }
                }
            }

            // Cari JSON objects yang mungkin berisi URLs
            val jsonPattern = """\{[^}]*["']url["']\s*:\s*["']([^"']+)["'][^}]*\}""".toRegex()
            jsonPattern.findAll(content).forEach { match ->
                val jsonUrl = match.groupValues[1]
                if (jsonUrl.contains("archive.org") && 
                    jsonUrl.matches(".*\\.(mp4|mkv|avi|m4v).*".toRegex())) {
                    addExtractorLink(jsonUrl, referer, callback, "JSON")
                }
            }
        }
    }

    private suspend fun addExtractorLink(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
        sourceName: String
    ) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return

        try {
            // Resolve final URL jika perlu
            val finalUrl = if (cleanUrl.contains("archive.org") && !cleanUrl.contains("ia\\d+\\.archive\\.org".toRegex())) {
                resolveArchiveUrl(cleanUrl)
            } else {
                cleanUrl
            }

            val quality = extractQualityFromUrl(finalUrl)
            val linkName = "$sourceName - $name"

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = linkName,
                    url = finalUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer ?: mainUrl
                    this.quality = quality
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "*/*",
                        "Accept-Encoding" to "gzip, deflate, br",
                        "DNT" to "1",
                        "Connection" to "keep-alive"
                    )
                }
            )
        } catch (e: Exception) {
            println("Failed to add extractor link for $cleanUrl: ${e.message}")
        }
    }

    private suspend fun resolveArchiveUrl(url: String): String {
        return try {
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
                "DNT" to "1"
            )
            
            val response = app.get(url, headers = headers, allowRedirects = true)
            
            // Jika redirect ke direct streaming server
            if (response.url.contains("ia\\d+\\.archive\\.org".toRegex())) {
                response.url
            } else {
                url
            }
        } catch (e: Exception) {
            println("URL resolution failed: ${e.message}")
            url
        }
    }

    private fun extractQualityFromUrl(url: String): Int {
        return when {
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value  
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            url.contains("240", true) -> Qualities.P240.value
            url.contains("hd", true) -> Qualities.P720.value
            url.contains("sd", true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }
}
