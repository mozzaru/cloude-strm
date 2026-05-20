package com.kuramanime

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.FilemoonV2
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

// ─── FileMoon ────────────────────────────────────────────────────────────────
// FIX: Tambahkan alternatif domain filemoon dan override getUrl agar
//      tidak bergantung penuh pada FilemoonV2 yang mungkin sudah stale.
class FileMoon : FilemoonV2() {
    override var mainUrl = "https://filemoon.sx"
    override var name = "FileMoon"
}

class FileMoonIn : FilemoonV2() {
    override var mainUrl = "https://filemoon.in"
    override var name = "FileMoon"
}

// ─── Sunrong / Nyomo / Streamhide ────────────────────────────────────────────
class Sunrong : FilemoonV2() {
    override var mainUrl = "https://sunrong.my.id"
    override var name = "Sunrong"
}

class Nyomo : StreamSB() {
    override var name: String = "Nyomo"
    override var mainUrl = "https://nyomo.my.id"
}

class Streamhide : Filesim() {
    override var name: String = "Streamhide"
    override var mainUrl: String = "https://streamhide.to"
}

// ─── Linkbox ─────────────────────────────────────────────────────────────────
open class Lbx : ExtractorApi() {
    override val name = "Linkbox"
    override val mainUrl = "https://lbx.to"
    private val realUrl = "https://www.linkbox.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val token = Regex("""(?:/f/|/file/|\?id=)(\w+)""").find(url)?.groupValues?.get(1)
        val id =
            app.get("$realUrl/api/file/share_out_list/?sortField=utime&sortAsc=0&pageNo=1&pageSize=50&shareToken=$token")
                .parsedSafe<Responses>()?.data?.itemId
        app.get("$realUrl/api/file/detail?itemId=$id", referer = url)
            .parsedSafe<Responses>()?.data?.itemInfo?.resolutionList?.map { link ->
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        link.url ?: return@map null,
                        INFER_TYPE
                    ) {
                        this.referer = "$realUrl/"
                        this.quality = getQualityFromName(link.resolution)
                    }
                )
            }
    }

    data class Resolutions(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("resolution") val resolution: String? = null,
    )

    data class ItemInfo(
        @JsonProperty("resolutionList") val resolutionList: ArrayList<Resolutions>? = arrayListOf(),
    )

    data class Data(
        @JsonProperty("itemInfo") val itemInfo: ItemInfo? = null,
        @JsonProperty("itemId") val itemId: String? = null,
    )

    data class Responses(
        @JsonProperty("data") val data: Data? = null,
    )
}

// ─── Kuramadrive ─────────────────────────────────────────────────────────────
open class Kuramadrive : ExtractorApi() {
    override val name = "DriveKurama"
    override val mainUrl = "https://kuramadrive.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val req = app.get(url, referer = referer)
        val doc = req.document

        val title = doc.select("title").text()
        val token = doc.select("meta[name=csrf-token]").attr("content")
        val routeCheckAvl = doc.select("input#routeCheckAvl").attr("value")

        val json = app.get(
            routeCheckAvl, headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "X-CSRF-TOKEN" to token
            ),
            referer = url,
            cookies = req.cookies
        ).parsedSafe<Source>()

        callback.invoke(
            newExtractorLink(
                name,
                name,
                json?.url ?: return,
                INFER_TYPE
            ) {
                this.referer = "$mainUrl/"
                this.quality = getIndexQuality(title)
            }
        )
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private data class Source(
        @JsonProperty("url") val url: String,
    )
}

// ─── RPMShare ─────────────────────────────────────────────────────────────────
// FIX: URL embed di Kuramanime berbentuk https://kurama.rpmvip.com/#HASHID
//      Player ini me-load iframe/video secara dinamis via JS.
//      Solusi: ambil halaman embed, lalu cari src iframe atau m3u8/mp4 di script.
//      Jika tidak ada (JS-only), fallback ke iframe embed langsung.
class RPMShare : ExtractorApi() {
    override val name: String = "RPMShare"
    override val mainUrl: String = "https://kurama.rpmvip.com"
    override val requiresReferer: Boolean = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Normalisasi URL: ganti '#' ke '/e/' agar bisa di-fetch sebagai halaman embed
        // Contoh: https://kurama.rpmvip.com/#z6fqhj  →  https://kurama.rpmvip.com/e/z6fqhj
        val hashId = Regex("""[#/]([a-zA-Z0-9]+)\s*$""").find(url)?.groupValues?.get(1)
        val embedUrl = if (hashId != null) "$mainUrl/e/$hashId" else url

        val response = app.get(
            embedUrl,
            referer = referer ?: "https://v9.kuramanime.blog/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        )
        val document = response.document

        // Coba parse video URL dari tag <source> atau <video>
        val sourceTag = document.select("source[src]").attr("src")
            .ifBlank { document.select("video[src]").attr("src") }

        if (sourceTag.isNotBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = sourceTag,
                    type = INFER_TYPE
                ) {
                    quality = Qualities.Unknown.value
                    this.referer = embedUrl
                }
            )
            return
        }

        // Coba parse dari inline script: cari pola file/src/source yang berisi .mp4 atau .m3u8
        val scriptContent = document.select("script").joinToString("\n") { it.html() }

        val videoUrlPatterns = listOf(
            Regex("""(?:file|src|source)\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']"""),
            Regex("""["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']"""),
            Regex("""(?:hls|url)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""")
        )

        for (pattern in videoUrlPatterns) {
            val found = pattern.find(scriptContent)?.groupValues?.get(1)
            if (!found.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = found,
                        type = INFER_TYPE
                    ) {
                        quality = Qualities.Unknown.value
                        this.referer = embedUrl
                    }
                )
                return
            }
        }

        // Fallback: coba iframe src di dalam halaman embed
        val iframeSrc = document.select("iframe[src]").attr("src")
        if (iframeSrc.isNotBlank() && iframeSrc != embedUrl) {
            // Rekursi sekali ke iframe target
            getUrl(iframeSrc, embedUrl, subtitleCallback, callback)
        }
    }
}

// ─── StreamP2P ────────────────────────────────────────────────────────────────
// FIX: Sama seperti RPMShare — URL embed bisa berformat /#hashid atau /e/hashid.
//      Tambah normalisasi URL dan pola regex yang lebih lengkap.
class StreamP2P : ExtractorApi() {
    override var mainUrl = "https://kurama.p2pstream.online"
    override var name = "StreamP2P"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Normalisasi hash URL ke embed path
        val hashId = Regex("""[#/]([a-zA-Z0-9]+)\s*$""").find(url)?.groupValues?.get(1)
        val embedUrl = if (url.contains("#") && hashId != null) "$mainUrl/e/$hashId" else url

        val response = app.get(
            embedUrl,
            referer = referer ?: "https://v9.kuramanime.blog/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        )
        val document = response.document

        // Coba tag <source> atau <video>
        val sourceTag = document.select("source[src]").attr("src")
            .ifBlank { document.select("video[src]").attr("src") }

        if (sourceTag.isNotBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = sourceTag,
                    type = INFER_TYPE
                ) {
                    quality = Qualities.Unknown.value
                    this.referer = embedUrl
                }
            )
            return
        }

        // Parse dari script inline
        val scriptContent = document.select("script").joinToString("\n") { it.html() }

        val videoUrlPatterns = listOf(
            Regex("""(?:file|src|source)\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']"""),
            Regex("""["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']"""),
            Regex("""(?:hls|url)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""")
        )

        for (pattern in videoUrlPatterns) {
            val found = pattern.find(scriptContent)?.groupValues?.get(1)
            if (!found.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = found,
                        type = INFER_TYPE
                    ) {
                        quality = Qualities.Unknown.value
                        this.referer = embedUrl
                    }
                )
                return
            }
        }

        // Fallback: iframe rekursi
        val iframeSrc = document.select("iframe[src]").attr("src")
        if (iframeSrc.isNotBlank() && iframeSrc != embedUrl) {
            getUrl(iframeSrc, embedUrl, subtitleCallback, callback)
        }
    }
}

// ─── Doodstream ───────────────────────────────────────────────────────────────
class Doodstream : DoodLaExtractor() {
    override var mainUrl = "https://dood.li"
    override var name = "Doodstream"
}
