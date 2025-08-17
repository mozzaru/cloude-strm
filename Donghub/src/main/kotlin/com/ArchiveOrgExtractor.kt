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
        val safeUrl = url.trim().replace(" ", "%20")
        val usedReferer = referer ?: mainUrl

        println("ArchiveOrgExtractor: start for $safeUrl (referer=$usedReferer)")

        // quick direct accept
        if (safeUrl.endsWith(".mp4", true) || safeUrl.contains(".mp4?", true) ||
            safeUrl.endsWith(".m3u8", true) || safeUrl.contains(".m3u8?", true)
        ) {
            println("ArchiveOrgExtractor: direct media url detected: $safeUrl")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = safeUrl,
                    type = if (safeUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = usedReferer
                    this.quality = getQualityFromName(safeUrl)
                }
            )
            return
        }

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to usedReferer
        )

        // try HEAD to follow redirects
        try {
            val headResp = app.head(safeUrl, headers = headers)
            val final = headResp.url
            println("ArchiveOrgExtractor: head final url = $final")
            if (!final.isNullOrBlank() && (final.endsWith(".mp4", true) || final.contains(".m3u8", true) || final.contains(".mp4", true))) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = final,
                        type = if (final.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = usedReferer
                        this.quality = getQualityFromName(final)
                    }
                )
                return
            }
        } catch (e: Exception) {
            println("ArchiveOrgExtractor: HEAD failed: ${e.message}")
        }

        // GET full page and search raw text (important: fetch("...mp4") pattern)
        try {
            val resp = app.get(safeUrl, headers = headers)
            val raw = resp.text
            println("ArchiveOrgExtractor: page length = ${raw.length}")

            // Patterns to find mp4/m3u8 in JS/HTML
            val patterns = listOf(
                Regex("""fetch\(\s*['"]([^'"]+?\.(?:mp4|m3u8)[^'"]*)['"]""", RegexOption.IGNORE_CASE),
                Regex("""['"]file['"]\s*[:=]\s*['"]([^'"]+?\.(?:mp4|m3u8)[^'"]*)['"]""", RegexOption.IGNORE_CASE),
                Regex("""https?://[^\s'")>]+?\.(?:mp4|m3u8)[^\s'")>]*""", RegexOption.IGNORE_CASE)
            )

            val found = linkedSetOf<String>()
            for (p in patterns) {
                p.findAll(raw).forEach { m ->
                    val candidate = (m.groups[1]?.value ?: m.value).trim()
                    if (candidate.isNotBlank()) {
                        var f = candidate
                        if (f.startsWith("//")) f = "https:$f"
                        if (!f.startsWith("http")) {
                            f = if (safeUrl.endsWith("/")) "$safeUrl$f" else "${safeUrl.substringBeforeLast('/')}/$f"
                        }
                        f = f.replace(" ", "%20")
                        found.add(f)
                    }
                }
            }

            if (found.isNotEmpty()) {
                println("ArchiveOrgExtractor: found ${found.size} media link(s)")
                for (f in found) {
                    val type = if (f.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = f,
                            type = type
                        ) {
                            this.referer = usedReferer
                            this.quality = getQualityFromName(f)
                        }
                    )
                }
                return
            }

            // fallback: DOM video tag
            val doc = resp.document
            doc.select("video source, video").forEach { elem ->
                val s = elem.attr("src").ifBlank { elem.attr("data-src") }
                if (!s.isNullOrBlank()) {
                    var f = s
                    if (f.startsWith("//")) f = "https:$f"
                    if (!f.startsWith("http")) f = "${safeUrl.substringBeforeLast('/')}/$f"
                    f = f.replace(" ", "%20")
                    println("ArchiveOrgExtractor: found video tag src = $f")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = f,
                            type = if (f.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = usedReferer
                            this.quality = getQualityFromName(f)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            println("ArchiveOrgExtractor: GET failed: ${e.message}")
            e.printStackTrace()
        }

        println("ArchiveOrgExtractor: no media links found for $safeUrl")
    }
}
