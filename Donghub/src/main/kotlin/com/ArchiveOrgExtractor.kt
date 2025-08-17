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

        // Direct link mp4/m3u8
        if (safeUrl.endsWith(".mp4", true) || safeUrl.endsWith(".m3u8", true)) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = safeUrl,
                    type   = if (safeUrl.endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer ?: mainUrl
                    this.quality = getQualityFromName(safeUrl) // deteksi angka resolusi dari nama file (720, 1080, dll)
                }
            )
            return
        }

        // fallback: coba HEAD redirect
        try {
            val resp = app.head(safeUrl)
            val final = resp.url
            if (final.endsWith(".mp4", true) || final.contains(".m3u8", true)) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = this.name,
                        url    = final,
                        type   = if (final.endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer ?: mainUrl
                        this.quality = getQualityFromName(final)
                    }
                )
                return
            }
        } catch (_: Exception) {}
    }
}
