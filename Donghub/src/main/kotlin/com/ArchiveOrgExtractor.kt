package com.donghub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Base64
import org.jsoup.Jsoup

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
        val safeUrl = url.trim().replace(" ", "%20")
        val usedReferer = referer ?: mainUrl

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to usedReferer,
            "Accept" to "*/*"
        )

        try {
            val resp = app.get(safeUrl, headers = headers)
            val doc = resp.document

            // 1️⃣ Cari langsung di <video>/<source>
            val videoSrc = doc.select("video source, video")
                .mapNotNull { it.attr("src") }
                .firstOrNull { it.isNotBlank() }

            if (videoSrc != null) {
                pushExtractor(videoSrc, usedReferer, callback)
            }

            // 2️⃣ Cari dari mirror (option value = base64 HTML)
            doc.select("select.mirror option").forEach { opt ->
                val encoded = opt.attr("value").trim()
                if (encoded.isNotBlank()) {
                    try {
                        val decoded = String(Base64.decode(encoded, Base64.DEFAULT))
                        val mirrorDoc = Jsoup.parse(decoded)
                        val src = mirrorDoc.select("video source, video")
                            .mapNotNull { it.attr("src") }
                            .firstOrNull { it.isNotBlank() }

                        if (src != null) {
                            pushExtractor(src, usedReferer, callback, mirrorName = opt.text())
                        }
                    } catch (e: Exception) {
                        println("Mirror decode failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            println("ArchiveOrgExtractor error: ${e.message}")
        }
    }

    private suspend fun pushExtractor(
        rawUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        mirrorName: String? = null
    ) {
        var finalUrl = rawUrl
        if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
        if (!finalUrl.startsWith("http")) {
            finalUrl = "$mainUrl/$finalUrl"
        }
        finalUrl = finalUrl.replace(" ", "%20")
    
        // 🔑 Resolve redirect ke link akhir (iaXXXX.archive.org)
        val resolvedUrl = try {
            app.get(finalUrl, headers = mapOf("User-Agent" to USER_AGENT)).url
        } catch (e: Exception) {
            finalUrl // fallback kalau gagal resolve
        }
    
        callback.invoke(
            newExtractorLink(
                source = name,
                name = mirrorName ?: name,
                url = resolvedUrl,
                type = if (resolvedUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(resolvedUrl)
            }
        )
    }
}
