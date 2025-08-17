package com.donghub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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
        val safeUrl = url.replace(" ", "%20")
        val headers = mapOf(
            "Referer" to (referer ?: mainUrl),
            "User-Agent" to "Mozilla/5.0 (Android)"
        )

        // Direct mp4 / m3u8 -> buat ExtractorLink dengan helper baru
        if (safeUrl.endsWith(".mp4", true) || safeUrl.endsWith(".m3u8", true)) {
            // newExtractorLink adalah helper modern (hindari constructor deprecated)
            val link = newExtractorLink(
                name = name,
                url = safeUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = safeUrl.endsWith(".m3u8", true),
                headers = headers
            )
            callback.invoke(link)
            return
        }

        // fallback: coba HEAD untuk mendapatkan final redirect (jika ada)
        try {
            val resp = app.head(safeUrl, headers = headers)
            val final = resp.url
            if (final.endsWith(".mp4", true) || final.contains(".m3u8", true)) {
                val link = newExtractorLink(
                    name = name,
                    url = final,
                    quality = Qualities.Unknown.value,
                    isM3u8 = final.contains(".m3u8", true),
                    headers = headers
                )
                callback.invoke(link)
                return
            }
        } catch (_: Exception) {
            // ignore
        }

        // terakhir: ambil halaman dan cari <video> / script mp4/m3u8 (opsional)
        try {
            val resp = app.get(safeUrl, headers = headers)
            val doc = resp.document

            doc.select("video source, video").forEach { v ->
                val src = v.attr("src").ifBlank { v.attr("data-src") }
                if (!src.isNullOrBlank()) {
                    val f = if (src.startsWith("http")) src else "https:$src"
                    val link = newExtractorLink(
                        name = name,
                        url = f,
                        quality = Qualities.Unknown.value,
                        isM3u8 = f.contains(".m3u8", true),
                        headers = headers
                    )
                    callback.invoke(link)
                }
            }

            val scriptsText = doc.select("script").joinToString("\n") { it.html() }
            val urlRegex = Regex("https?://[^\\s'\"]+?(?:\\.m3u8|\\.mp4)[^\\s'\"]*")
            urlRegex.findAll(scriptsText).forEach { m ->
                val f = m.value
                val link = newExtractorLink(
                    name = name,
                    url = f,
                    quality = Qualities.Unknown.value,
                    isM3u8 = f.contains(".m3u8", true),
                    headers = headers
                )
                callback.invoke(link)
            }
        } catch (_: Exception) {
            // ignore
        }
    }
}
