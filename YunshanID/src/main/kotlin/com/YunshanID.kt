package com.yunshanid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// ─── Model JSON dari /api/donghuas ───────────────────────────────────────────

@Serializable
data class DonghuaItem(
    val id: Int?                    = null,
    val title: String?              = null,
    val slug: String?               = null,
    @SerialName("poster_url")
    val posterUrl: String?          = null,
    val poster: String?             = null,
    val synopsis: String?           = null,
    val status: String?             = null,
    val type: String?               = null,
    @SerialName("latest_ep")
    val latestEp: Int?              = null,
    @SerialName("view_count")
    val viewCount: Int?             = null,
    @SerialName("last_update")
    val lastUpdate: String?         = null,
    val updatedAt: String?          = null,
    val createdAt: String?          = null,
    @SerialName("episodes_map")
    val episodesMap: List<Int>?     = null,
    val episodes: List<EpisodeItem>? = null
)

@Serializable
data class EpisodeItem(
    val id: Int?                    = null,
    @SerialName("ep_number")
    val epNumber: Int?              = null,
    val episodeNumber: Int?         = null, // legacy
    val title: String?              = null,
    @SerialName("video_url")
    val videoUrl: String?           = null,
    val servers: List<ServerItem>?  = null,
    val createdAt: String?          = null,
    @SerialName("view_count")
    val viewCount: Int?             = null
)

@Serializable
data class ServerItem(
    val name: String?       = null,
    val url: String?        = null,
    @SerialName("embed_url")
    val embedUrl: String?   = null
)

class YunshanID : MainAPI() {

    override var mainUrl            = "https://yunshanid.site"
    override var name               = "YunshanID"
    override val hasMainPage        = true
    override val hasDownloadSupport = true
    override var lang               = "id"
    override val supportedTypes     = setOf(TvType.Anime, TvType.Movie)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    private val apiHeaders = mapOf(
        "User-Agent"         to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
        "Accept"             to "*/*",
        "Accept-Language"    to "id-ID,id;q=0.9",
        "Content-Type"       to "application/json",
        "Sec-Ch-Ua"          to "\"Chromium\";v=\"147\", \"Not.A/Brand\";v=\"8\"",
        "Sec-Ch-Ua-Mobile"   to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest"     to "empty",
        "Sec-Fetch-Mode"     to "cors",
        "Sec-Fetch-Site"     to "same-origin",
        "Referer"            to "https://yunshanid.site/"
    )

    private val pageHeaders = mapOf(
        "User-Agent"                to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
        "Accept"                    to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language"           to "id-ID,id;q=0.9",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Ch-Ua"                 to "\"Chromium\";v=\"147\", \"Not.A/Brand\";v=\"8\"",
        "Sec-Ch-Ua-Mobile"          to "?1",
        "Sec-Ch-Ua-Platform"        to "\"Android\"",
        "Sec-Fetch-Dest"            to "document",
        "Sec-Fetch-Mode"            to "navigate",
        "Sec-Fetch-Site"            to "same-origin",
        "Referer"                   to "https://yunshanid.site/"
    )

    // ─── Fetch semua donghua dari API ─────────────────────────────────────────
    private suspend fun fetchAllDonghuas(): List<DonghuaItem> {
        return try {
            val resp = app.get("$mainUrl/api/donghuas", headers = apiHeaders)
            json.decodeFromString<List<DonghuaItem>>(resp.text)
        } catch (e: Exception) {
            println("❌ [YunshanID] fetchAllDonghuas: ${e.message}")
            emptyList()
        }
    }

    // ─── Konversi ke SearchResponse ───────────────────────────────────────────
    private fun DonghuaItem.toSearchResponse(): SearchResponse? {
        val id    = this.id ?: return null
        val title = this.title?.trim()?.ifBlank { null } ?: return null
        val url   = "$mainUrl/synopsis/$id"
        val type  = if (this.type?.lowercase() == "movie") TvType.Movie else TvType.Anime

        return newAnimeSearchResponse(title, url, type) {
            this.posterUrl = this@toSearchResponse.posterUrl ?: this@toSearchResponse.poster
            addSub(latestEp)
        }
    }

    // ─── MAIN PAGE ────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "latest"    to "Rilisan Terbaru",
        "popular"   to "Populer",
        "ongoing"   to "Sedang Tayang",
        "completed" to "Selesai",
        "hiatus"    to "Hiatus",
        "movie"     to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val all = fetchAllDonghuas()
        if (all.isEmpty()) return newHomePageResponse(
            list = HomePageList(request.name, emptyList(), false), hasNext = false
        )

        val pageSize = 20
        val offset   = (page - 1) * pageSize

        val filtered: List<DonghuaItem> = when (request.data) {
            "latest" -> all.sortedByDescending { it.lastUpdate ?: it.updatedAt ?: it.createdAt ?: "" }

            "popular" -> all.sortedByDescending { it.viewCount ?: 0 }

            "ongoing" -> all
                .filter { it.status?.lowercase()?.contains("going") == true }
                .sortedByDescending { it.lastUpdate ?: it.updatedAt ?: it.createdAt ?: "" }

            "completed" -> all
                .filter { it.status?.lowercase()?.contains("complete") == true }
                .sortedByDescending { it.lastUpdate ?: it.updatedAt ?: it.createdAt ?: "" }

            "hiatus" -> all
                .filter { it.status?.lowercase()?.contains("hiatus") == true }
                .sortedByDescending { it.lastUpdate ?: it.updatedAt ?: it.createdAt ?: "" }

            "movie" -> all
                .filter { it.type?.lowercase() == "movie" }
                .sortedByDescending { it.lastUpdate ?: it.updatedAt ?: it.createdAt ?: "" }

            else -> all
        }

        val paged   = filtered.drop(offset).take(pageSize)
        val hasNext = filtered.size > offset + pageSize

        return newHomePageResponse(
            list    = HomePageList(request.name, paged.mapNotNull { it.toSearchResponse() }, false),
            hasNext = hasNext
        )
    }

    // ─── SEARCH ───────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.lowercase().trim()
        return fetchAllDonghuas()
            .filter {
                it.title?.lowercase()?.contains(q) == true ||
                it.slug?.lowercase()?.contains(q) == true
            }
            .sortedByDescending { it.viewCount ?: 0 }
            .mapNotNull { it.toSearchResponse() }
    }

    // ─── DETAIL HALAMAN ───────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val id   = url.substringAfterLast("/").toIntOrNull() ?: throw Exception("Invalid URL: $url")
        val item = try {
            val resp = app.get("$mainUrl/api/donghua/$id", headers = apiHeaders)
            json.decodeFromString<DonghuaItem>(resp.text)
        } catch (e: Exception) {
            println("❌ [YunshanID] load: ${e.message}")
            // Fallback to fetchAll if single fetch fails
            fetchAllDonghuas().find { it.id == id } ?: throw Exception("Donghua not found: $id")
        }

        val title   = item.title?.trim() ?: "Unknown"
        val isMovie = item.type?.lowercase() == "movie"

        val showStatus = when {
            item.status?.lowercase()?.contains("going") == true -> ShowStatus.Ongoing
            item.status?.lowercase()?.contains("complete") == true -> ShowStatus.Completed
            else -> null
        }

        // Episode list ascending (ep 1, ep 2, …)
        val sortedEps = (item.episodes ?: emptyList())
            .sortedBy { it.epNumber ?: it.episodeNumber ?: 0 }

        val poster = item.posterUrl ?: item.poster

        return if (isMovie || (sortedEps.isEmpty() && item.episodesMap.isNullOrEmpty())) {
            val epUrl = if (sortedEps.isNotEmpty())
                "$mainUrl/episode/${sortedEps.first().id}"
            else url

            newMovieLoadResponse(title, url, TvType.Movie, epUrl) {
                this.posterUrl = poster
                this.plot      = item.synopsis
            }
        } else {
            val episodes = if (sortedEps.isNotEmpty()) {
                sortedEps.mapNotNull { ep ->
                    val epId = ep.id ?: return@mapNotNull null
                    newEpisode("$mainUrl/episode/$epId") {
                        this.name      = ep.title?.takeIf { it.isNotBlank() }
                            ?: "Episode ${ep.epNumber ?: ep.episodeNumber}"
                        this.episode   = ep.epNumber ?: ep.episodeNumber
                        this.posterUrl = poster
                    }
                }
            } else {
                // Fallback use episodes_map if episodes list is empty
                item.episodesMap?.mapIndexed { index, epId ->
                    newEpisode("$mainUrl/episode/$epId") {
                        this.name    = "Episode ${index + 1}"
                        this.episode = index + 1
                        this.posterUrl = poster
                    }
                } ?: emptyList()
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl  = poster
                this.plot       = item.synopsis
                this.showStatus = showStatus
            }
        }
    }

    // ─── LOAD LINKS ───────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epId = data.substringAfterLast("/").toIntOrNull()
            ?: return loadLinksFromPage(data, subtitleCallback, callback)

        // Cari donghua_id dari halaman episode (biasanya ada di breadcrumb atau meta)
        // Namun karena kita sudah punya epId, kita bisa coba cari di all donghuas atau detail donghua
        // Strategi: Cari di semua donghua (fetchAll) atau iterate detail (mahal tapi akurat)
        // Lebih baik cari videoUrl dari detail API /api/donghua/{id} jika kita tau id-nya.
        // Karena epId tidak memberi kita donghuaId langsung, kita cari di fetchAllDonghuas().

        try {
            val all = fetchAllDonghuas()
            for (d in all) {
                // episodes list di fetchAll sekarang null atau partial, tapi mari kita cek
                d.episodes?.find { it.id == epId }?.let { ep ->
                    if (processEpisode(ep, data, subtitleCallback, callback)) return true
                }

                // Jika epId ada di episodesMap, maka ini donghuanya
                if (d.episodesMap?.contains(epId) == true) {
                    val resp = app.get("$mainUrl/api/donghua/${d.id}", headers = apiHeaders)
                    val detail = json.decodeFromString<DonghuaItem>(resp.text)
                    detail.episodes?.find { it.id == epId }?.let { ep ->
                        if (processEpisode(ep, data, subtitleCallback, callback)) return true
                    }
                }
            }
        } catch (e: Exception) {
            println("⚠️ [YunshanID] loadLinks search: ${e.message}")
        }

        // Fallback parse HTML halaman episode
        return loadLinksFromPage(data, subtitleCallback, callback)
    }

    private suspend fun processEpisode(
        ep: EpisodeItem,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val v = ep.videoUrl?.trim()
        if (!v.isNullOrBlank()) {
            val final = normalizeUrl(v)
            println("🎯 [YunshanID] videoUrl → $final")
            if (loadExtractor(final, referer, subtitleCallback, callback)) {
                found = true
            }
        }

        ep.servers?.forEach { server ->
            val sUrl = server.embedUrl ?: server.url
            if (!sUrl.isNullOrBlank()) {
                val final = normalizeUrl(sUrl)
                println("🎯 [YunshanID] server ${server.name} → $final")
                if (loadExtractor(final, referer, subtitleCallback, callback)) {
                    found = true
                }
            }
        }
        return found
    }

    private fun normalizeUrl(url: String): String = when {
        url.startsWith("//")   -> "https:$url"
        url.startsWith("http") -> url
        else                   -> "$mainUrl$url"
    }

    // ─── Fallback parse HTML ──────────────────────────────────────────────────
    private suspend fun loadLinksFromPage(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(url, headers = pageHeaders).document

            // Cari iframe
            val iframeSrc = doc.selectFirst("iframe")?.let { f ->
                f.attr("src").ifBlank { f.attr("data-src") }
            }?.trim()

            if (!iframeSrc.isNullOrBlank()) {
                val final = normalizeUrl(iframeSrc)
                println("🎯 [YunshanID] iframe → $final")
                loadExtractor(final, url, subtitleCallback, callback)
                return true
            }

            // Cari di script
            val scripts = doc.select("script").joinToString("\n") { it.html() }
            val patterns = listOf(
                Regex("""videoUrl\s*[:=]\s*["'`]([^"'`\s]+)"""),
                Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)"""),
                Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)"""),
                Regex("""file\s*:\s*["'`](https?://[^"'`\s]+)"""),
                Regex("""src\s*:\s*["'`](https?://[^"'`\s]+)""")
            )
            for (p in patterns) {
                val found = p.find(scripts)?.groupValues?.getOrNull(1)?.trim()
                    ?.takeIf { it.length > 10 } ?: continue
                println("🎯 [YunshanID] script → $found")
                loadExtractor(found, url, subtitleCallback, callback)
                return true
            }

            println("❌ [YunshanID] No video at $url")
            false
        } catch (e: Exception) {
            println("❌ [YunshanID] loadLinksFromPage: ${e.message}")
            false
        }
    }
}
