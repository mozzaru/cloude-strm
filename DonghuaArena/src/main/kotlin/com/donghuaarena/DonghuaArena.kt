package com.donghuaarena

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class DonghuaItem(
    @JsonProperty("id")           val id: String?      = null,
    @JsonProperty("title")        val title: String?   = null,
    @JsonProperty("description")  val description: String?  = null,
    @JsonProperty("poster_url")   val posterUrl: String?    = null,
    @JsonProperty("rating")       val rating: Double?  = null,
    @JsonProperty("status")       val status: String?  = null,
    @JsonProperty("eps")          val eps: Int?        = null,
    @JsonProperty("genres")       val genres: Array<Int>?   = null,
    @JsonProperty("release_day")  val releaseDay: Any?      = null,
    @JsonProperty("release_time") val releaseTime: String?  = null,
    @JsonProperty("last_update")  val lastUpdate: String?   = null,
    @JsonProperty("updated_at")   val updatedAt: String?    = null
)

data class GenreItem(
    @JsonProperty("id")   val id: Int,
    @JsonProperty("name") val name: String
)

data class EpisodeItem(
    @JsonProperty("id")             val id: String,
    @JsonProperty("donghua_id")     val donghuaId: String?  = null,
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("video_url")      val videoUrl: String?   = null
)

data class ServerItem(
    @JsonProperty("id")         val id: Int?     = null,
    @JsonProperty("episode_id") val episodeId: String? = null,
    @JsonProperty("name")       val name: String?      = null,
    @JsonProperty("url")        val url: String?       = null
)

class DonghuaArena : MainAPI() {
    override var mainUrl        = "https://donghuaarena.site"
    override var name           = "Donghua Arena"
    override val hasMainPage    = true
    override var lang           = "id"
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val TAG = "DonghuaArena"

        @Volatile private var genreCache: Map<Int, String> = emptyMap()
        @Volatile private var genreFetchedAt: Long = 0L
        private const val GENRE_TTL_MS = 15 * 60 * 1000L

        val NO_CACHE = mapOf(
            "Cache-Control" to "no-cache, no-store, must-revalidate",
            "Pragma"        to "no-cache",
            "Expires"       to "0"
        )

        fun noCacheUrl(url: String): String {
            val sep = if (url.contains("?")) "&" else "?"
            return "$url${sep}_=${System.currentTimeMillis()}"
        }

        fun cleanPosterUrl(posterUrl: String?, lastUpdate: String?, updatedAt: String?): String? {
            if (posterUrl.isNullOrBlank()) return null

            val directUrl = if (posterUrl.contains("wsrv.nl")) {
                try {
                    val urlParam = posterUrl
                        .substringAfter("wsrv.nl/?url=")
                        .substringBefore("&")
                    if (urlParam.isNotBlank())
                        java.net.URLDecoder.decode(urlParam, "UTF-8")
                    else posterUrl
                } catch (e: Exception) {
                    posterUrl
                }
            } else {
                posterUrl
            }

            val version = (lastUpdate ?: updatedAt)
                ?.replace(Regex("[^0-9]"), "")
                ?.take(12)
                ?.trimStart('0')

            return if (!version.isNullOrBlank()) {
                val sep = if (directUrl.contains("?")) "&" else "?"
                "$directUrl${sep}_v=$version"
            } else directUrl
        }
    }

    override val mainPage = mainPageOf(
        "api/donghuas" to "Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "getMainPage page=$page")

        val items = try {
            app.get(
                noCacheUrl("$mainUrl/${request.data}"),
                headers = NO_CACHE,
                timeout = 15
            ).parsed<Array<DonghuaItem>>()
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage error: ${e.message}")
            return newHomePageResponse(
                list = HomePageList(name = request.name, list = emptyList(), isHorizontalImages = false),
                hasNext = false
            )
        }

        val searchResponses = coroutineScope {
            items.map { item ->
                async { item.toSearchResponse() }
            }.awaitAll()
        }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = searchResponses, isHorizontalImages = false),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "search: $query")

        val items = try {
            app.get(
                noCacheUrl("$mainUrl/api/donghuas"),
                headers = NO_CACHE,
                timeout = 15
            ).parsed<Array<DonghuaItem>>()
        } catch (e: Exception) {
            Log.e(TAG, "search error: ${e.message}")
            return emptyList()
        }

        val lowerQuery = query.lowercase()
        return items
            .filter {
                it.title?.lowercase()?.contains(lowerQuery) == true ||
                it.description?.lowercase()?.contains(lowerQuery) == true
            }
            .map { it.toSearchResponse() }
    }

    private suspend fun getGenres(): Map<Int, String> {
        val now = System.currentTimeMillis()
        if (genreCache.isNotEmpty() && (now - genreFetchedAt) < GENRE_TTL_MS) {
            return genreCache
        }
        return try {
            val fresh = app.get(
                "$mainUrl/api/genres",
                headers = NO_CACHE,
                timeout = 10
            ).parsed<Array<GenreItem>>().associate { it.id to it.name }
            genreCache     = fresh
            genreFetchedAt = now
            fresh
        } catch (e: Exception) {
            Log.e(TAG, "getGenres error: ${e.message}")
            genreCache
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load: $url")
        val id = url.removePrefix("$mainUrl/anime/")

        val (item, episodesRaw, genreMap) = coroutineScope {
            val itemDef = async {
                app.get(
                    noCacheUrl("$mainUrl/api/donghuas/$id"),
                    headers = NO_CACHE,
                    timeout = 15
                ).parsed<DonghuaItem>()
            }
            val epDef = async {
                try {
                    app.get(
                        noCacheUrl("$mainUrl/api/episodes?donghua_id=$id"),
                        headers = NO_CACHE,
                        timeout = 15
                    ).parsed<Array<EpisodeItem>>()
                } catch (e: Exception) {
                    Log.e(TAG, "episodes error: ${e.message}")
                    emptyArray<EpisodeItem>()
                }
            }
            val genreDef = async { getGenres() }

            Triple(itemDef.await(), epDef.await(), genreDef.await())
        }

        Log.d(TAG, "parallel fetch done — eps=${episodesRaw.size}")

        val genres   = item.genres?.mapNotNull { genreMap[it] }
        val episodes = episodesRaw
            .sortedByDescending { it.episodeNumber }
            .map { ep ->
                newEpisode("${ep.id}|${ep.videoUrl ?: ""}") {
                    this.name    = "Episode ${ep.episodeNumber}"
                    this.episode = ep.episodeNumber
                }
            }

        val status = when {
            item.status.equals("End", true) ||
            item.status.equals("Completed", true) -> ShowStatus.Completed
            item.status.equals("Ongoing", true)   -> ShowStatus.Ongoing
            else                                   -> null
        }

        return newTvSeriesLoadResponse(item.title ?: "", url, TvType.Anime, episodes) {
            this.posterUrl  = cleanPosterUrl(item.posterUrl, item.lastUpdate, item.updatedAt)
            this.plot       = item.description
            this.score      = Score.from10(item.rating)
            this.showStatus = status
            this.tags       = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks: $data")
        val parts      = data.split("|")
        val epId       = parts.getOrNull(0)?.removePrefix("$mainUrl/")
        val primaryUrl = parts.getOrNull(1)?.takeIf { it.isNotBlank() }

        primaryUrl?.let {
            try { loadExtractor(it, "$mainUrl/", subtitleCallback, callback) }
            catch (e: Exception) { Log.e(TAG, "primary error: ${e.message}") }
        }

        epId?.let { id ->
            val servers = try {
                app.get(
                    noCacheUrl("$mainUrl/api/servers?episode_id=$id"),
                    headers = NO_CACHE,
                    timeout = 10
                ).parsed<Array<ServerItem>>()
            } catch (e: Exception) {
                Log.e(TAG, "servers error: ${e.message}")
                emptyArray()
            }

            for (server in servers) {
                val sUrl = server.url ?: continue
                if (sUrl.equals(primaryUrl, ignoreCase = true)) continue
                try { loadExtractor(sUrl, "$mainUrl/", subtitleCallback, callback) }
                catch (e: Exception) { Log.e(TAG, "mirror [${server.name}] error: ${e.message}") }
            }
        }

        return true
    }

    private fun DonghuaItem.toSearchResponse(): SearchResponse {
        val isCompleted = status.equals("End", true) || status.equals("Completed", true)
        val statusLabel = if (isCompleted) " (Completed)" else ""

        val days = releaseDay?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.mapNotNull { d ->
                when (d.trim()) {
                    "1" -> "Senin";  "2" -> "Selasa"; "3" -> "Rabu"
                    "4" -> "Kamis";  "5" -> "Jumat";  "6" -> "Sabtu"
                    "7" -> "Minggu"; else -> null
                }
            }?.joinToString(", ")

        val scheduleLabel = if (!days.isNullOrBlank() && status.equals("Ongoing", true))
            " [$days | ${releaseTime ?: ""}]".trimEnd()
        else ""

        return newAnimeSearchResponse(
            name = (title ?: "") + statusLabel + scheduleLabel,
            url  = "$mainUrl/anime/$id",
            type = TvType.Anime
        ) {
            this.posterUrl = cleanPosterUrl(
                this@toSearchResponse.posterUrl,
                lastUpdate,
                updatedAt
            )
            addDubStatus(DubStatus.Subbed)
            eps?.let { addSub(it) }
        }
    }
}
