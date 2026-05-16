package com.yunshanid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// ─── Model JSON dari /api/donghuas ───────────────────────────────────────────

@Serializable
data class DonghuaItem(
    val id: Int?              = null,
    val title: String?        = null,
    val slug: String?         = null,
    val poster: String?       = null,
    val synopsis: String?     = null,
    val status: String?       = null,
    val type: String?         = null,
    val totalEpisodes: Int?   = null,
    val views: Int?           = null,
    val updatedAt: String?    = null,
    val createdAt: String?    = null,
    val episodes: List<EpisodeItem>? = null
)

@Serializable
data class EpisodeItem(
    val id: Int?            = null,
    val episodeNumber: Int? = null,
    val title: String?      = null,
    val videoUrl: String?   = null,
    val createdAt: String?  = null,
    val views: Int?         = null
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

        val latestEp = this.episodes
            ?.maxByOrNull { it.episodeNumber ?: 0 }
            ?.episodeNumber

        return newAnimeSearchResponse(title, url, type) {
            this.posterUrl = this@toSearchResponse.poster
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

        // Helper: timestamp episode terbaru dalam suatu series
        fun DonghuaItem.latestEpTimestamp(): String =
            episodes?.maxByOrNull { ep ->
                ep.createdAt ?: ep.id?.toString() ?: ""
            }?.createdAt ?: updatedAt ?: createdAt ?: ""

        val filtered: List<DonghuaItem> = when (request.data) {
            // FRESH UPDATE: urut berdasarkan episode.createdAt terbaru
            "latest" -> all
                .filter { it.episodes?.isNotEmpty() == true }
                .sortedByDescending { it.latestEpTimestamp() }

            "popular" -> all.sortedByDescending { it.views ?: 0 }

            "ongoing" -> all
                .filter { it.status?.lowercase() == "ongoing" }
                .sortedByDescending { it.latestEpTimestamp() }

            "completed" -> all
                .filter { it.status?.lowercase() == "completed" }
                .sortedByDescending { it.updatedAt ?: "" }

            "hiatus" -> all
                .filter { it.status?.lowercase() == "hiatus" }
                .sortedByDescending { it.updatedAt ?: "" }

            "movie" -> all
                .filter { it.type?.lowercase() == "movie" }
                .sortedByDescending { it.updatedAt ?: "" }

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
            .sortedByDescending { it.views ?: 0 }
            .mapNotNull { it.toSearchResponse() }
    }

    // ─── DETAIL HALAMAN ───────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val id   = url.substringAfterLast("/").toIntOrNull()
        val item = fetchAllDonghuas().find { it.id == id }
            ?: return newMovieLoadResponse("Unknown", url, TvType.Anime, url)

        val title   = item.title?.trim() ?: "Unknown"
        val isMovie = item.type?.lowercase() == "movie"

        val showStatus = when (item.status?.lowercase()) {
            "ongoing"   -> ShowStatus.Ongoing
            "completed" -> ShowStatus.Completed
            else        -> null
        }

        // Episode list ascending (ep 1, ep 2, …)
        val sortedEps = (item.episodes ?: emptyList())
            .sortedBy { it.episodeNumber ?: 0 }

        return if (isMovie || sortedEps.isEmpty()) {
            val epUrl = if (sortedEps.isNotEmpty())
                "$mainUrl/episode/${sortedEps.first().id}"
            else url

            newMovieLoadResponse(title, url, TvType.Movie, epUrl) {
                this.posterUrl = item.poster
                this.plot      = item.synopsis
            }
        } else {
            val episodes = sortedEps.mapNotNull { ep ->
                val epId = ep.id ?: return@mapNotNull null
                newEpisode("$mainUrl/episode/$epId") {
                    this.name      = ep.title?.takeIf { it.isNotBlank() }
                        ?: "Episode ${ep.episodeNumber}"
                    this.episode   = ep.episodeNumber
                    this.posterUrl = item.poster
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl  = item.poster
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

        // 1. Coba /api/episodes/{id} langsung
        try {
            val ep = json.decodeFromString<EpisodeItem>(
                app.get("$mainUrl/api/episodes/$epId", headers = apiHeaders).text
            )
            val v = ep.videoUrl?.trim()
            if (!v.isNullOrBlank()) {
                val final = normalizeUrl(v)
                println("🎯 [YunshanID] /api/episodes/$epId → $final")
                loadExtractor(final, data, subtitleCallback, callback)
                return true
            }
        } catch (e: Exception) {
            println("⚠️ [YunshanID] /api/episodes/$epId: ${e.message}")
        }

        // 2. Coba ambil dari data inline /api/donghuas
        try {
            outer@ for (donghua in fetchAllDonghuas()) {
                for (ep in (donghua.episodes ?: emptyList())) {
                    if (ep.id != epId) continue
                    val v = ep.videoUrl?.trim()
                    if (!v.isNullOrBlank()) {
                        val final = normalizeUrl(v)
                        println("🎯 [YunshanID] donghuas-inline → $final")
                        loadExtractor(final, data, subtitleCallback, callback)
                        return true
                    }
                    break@outer
                }
            }
        } catch (e: Exception) {
            println("⚠️ [YunshanID] inline search: ${e.message}")
        }

        // 3. Fallback parse HTML halaman episode
        return loadLinksFromPage(data, subtitleCallback, callback)
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
