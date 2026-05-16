package com.yunshanid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// ─── Model JSON dari API ───────────────────────────────────────────────────

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

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
        "Referer" to "https://yunshanid.site/",
        "Accept" to "*/*"
    )

    // ─── Fetch semua donghua dari API ─────────────────────────────────────────
    private suspend fun fetchAllDonghuas(): List<DonghuaItem> {
        val url = "$mainUrl/api/donghuas?t=${System.currentTimeMillis()}"
        return try {
            val resp = app.get(url, headers = commonHeaders, timeout = 60)
            if (resp.code != 200) {
                println("⚠️ [YunshanID] API returned code ${resp.code}")
            }
            val text = resp.text
            if (text.isBlank()) return emptyList()

            // Debug print first 100 chars
            // println("📢 [YunshanID] API Response: ${text.take(100)}")

            json.decodeFromString<List<DonghuaItem>>(text)
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

        val isMovie = this.type?.contains("movie", ignoreCase = true) == true
        val type = if (isMovie) TvType.Movie else TvType.Anime

        val poster = this.posterUrl ?: this.poster

        return newAnimeSearchResponse(title, url, type) {
            this.posterUrl = fixUrlNull(poster)
            if (type == TvType.Anime) {
                addSub(latestEp)
            }
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
        if (all.isEmpty()) {
            return newHomePageResponse(
                list = HomePageList(request.name, emptyList(), false), hasNext = false
            )
        }

        val pageSize = 24
        val offset   = (page - 1) * pageSize

        val filtered: List<DonghuaItem> = when (request.data) {
            "latest" -> all.sortedByDescending { it.lastUpdate ?: it.updatedAt ?: it.createdAt ?: "" }

            "popular" -> all.sortedByDescending { it.viewCount ?: 0 }

            "ongoing" -> all
                .filter { it.status?.contains("going", ignoreCase = true) == true }
                .sortedByDescending { it.lastUpdate ?: it.updatedAt ?: it.createdAt ?: "" }

            "completed" -> all
                .filter { it.status?.contains("complete", ignoreCase = true) == true || it.status?.contains("selesai", ignoreCase = true) == true }
                .sortedByDescending { it.lastUpdate ?: it.updatedAt ?: it.createdAt ?: "" }

            "hiatus" -> all
                .filter { it.status?.contains("hiatus", ignoreCase = true) == true || it.status?.contains("tunda", ignoreCase = true) == true }
                .sortedByDescending { it.lastUpdate ?: it.updatedAt ?: it.createdAt ?: "" }

            "movie" -> all
                .filter { it.type?.contains("movie", ignoreCase = true) == true }
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
        val id = url.substringAfterLast("/").toIntOrNull() ?: throw Exception("Invalid URL: $url")
        val detailUrl = "$mainUrl/api/donghua/$id?t=${System.currentTimeMillis()}"

        val item = try {
            val resp = app.get(detailUrl, headers = commonHeaders, timeout = 60)
            json.decodeFromString<DonghuaItem>(resp.text)
        } catch (e: Exception) {
            println("❌ [YunshanID] load details failed: ${e.message}")
            fetchAllDonghuas().find { it.id == id } ?: throw Exception("Donghua not found: $id")
        }

        val title = item.title?.trim() ?: "Unknown"
        val isMovie = item.type?.contains("movie", ignoreCase = true) == true
        val type = if (isMovie) TvType.Movie else TvType.Anime

        val showStatus = when {
            item.status?.contains("going", ignoreCase = true) == true -> ShowStatus.Ongoing
            item.status?.contains("complete", ignoreCase = true) == true -> ShowStatus.Completed
            else -> null
        }

        val sortedEps = (item.episodes ?: emptyList())
            .sortedBy { it.epNumber ?: it.episodeNumber ?: 0 }

        val poster = item.posterUrl ?: item.poster

        return if (isMovie || (sortedEps.isEmpty() && item.episodesMap.isNullOrEmpty())) {
            val epUrl = if (sortedEps.isNotEmpty())
                "$mainUrl/episode/${sortedEps.first().id}"
            else url

            newMovieLoadResponse(title, url, type, epUrl) {
                this.posterUrl = fixUrlNull(poster)
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
                        this.posterUrl = fixUrlNull(poster)
                    }
                }
            } else {
                item.episodesMap?.mapIndexed { index, epId ->
                    newEpisode("$mainUrl/episode/$epId") {
                        this.name    = "Episode ${index + 1}"
                        this.episode = index + 1
                        this.posterUrl = fixUrlNull(poster)
                    }
                } ?: emptyList()
            }

            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl  = fixUrlNull(poster)
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

        try {
            val all = fetchAllDonghuas()
            for (d in all) {
                d.episodes?.find { it.id == epId }?.let { ep ->
                    if (processEpisode(ep, data, subtitleCallback, callback)) return true
                }

                if (d.episodesMap?.contains(epId) == true) {
                    val resp = app.get("$mainUrl/api/donghua/${d.id}", headers = commonHeaders)
                    val detail = json.decodeFromString<DonghuaItem>(resp.text)
                    detail.episodes?.find { it.id == epId }?.let { ep ->
                        if (processEpisode(ep, data, subtitleCallback, callback)) return true
                    }
                }
            }
        } catch (e: Exception) {
            println("⚠️ [YunshanID] loadLinks search: ${e.message}")
        }

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
            if (loadExtractor(final, referer, subtitleCallback, callback)) {
                found = true
            }
        }

        ep.servers?.forEach { server ->
            val sUrl = server.embedUrl ?: server.url
            if (!sUrl.isNullOrBlank()) {
                val final = normalizeUrl(sUrl)
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

    private suspend fun loadLinksFromPage(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val iframeSrc = doc.selectFirst("iframe")?.let { f ->
                f.attr("src").ifBlank { f.attr("data-src") }
            }?.trim()

            if (!iframeSrc.isNullOrBlank()) {
                val final = normalizeUrl(iframeSrc)
                loadExtractor(final, url, subtitleCallback, callback)
                return true
            }

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
                loadExtractor(found, url, subtitleCallback, callback)
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}
