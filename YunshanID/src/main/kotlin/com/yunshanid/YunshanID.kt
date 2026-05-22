package com.yunshanid

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class YunshanID : MainAPI() {
    override var mainUrl = "https://yunshanid.site"
    override var name = "YunshanID"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    override val mainPage = mainPageOf(
        "latest"    to "Rilisan Terbaru",
        "popular"   to "Populer",
        "On-Going"  to "Ongoing",
        "Completed" to "Completed",
        "Movie"     to "Movie",
        "all"       to "Semua Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("$mainUrl/api/donghuas").text
        val allDonghuas = parseJson<List<DonghuaResponse>>(response)

        val items = when (request.data) {
            "latest"    -> allDonghuas.sortedByDescending { it.lastUpdate }.map { it.toSearchResponse() }
            "popular"   -> allDonghuas.sortedByDescending { it.viewCount }.map { it.toSearchResponse() }
            "On-Going"  -> allDonghuas.filter { it.status == "On-Going" }.map { it.toSearchResponse() }
            "Completed" -> allDonghuas.filter { it.status == "Completed" }.map { it.toSearchResponse() }
            "Movie"     -> allDonghuas.filter { it.type == "Movie" }.map { it.toSearchResponse() }
            else        -> allDonghuas.map { it.toSearchResponse() }
        }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$mainUrl/api/donghuas").text
        val allDonghuas = parseJson<List<DonghuaResponse>>(response)
        return allDonghuas
            .filter { it.title?.contains(query, ignoreCase = true) == true }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.split("/").last()
        val response = app.get("$mainUrl/api/donghua/$id").text
        val detail = parseJson<DonghuaDetailResponse>(response)

        val title = detail.title ?: ""
        val poster = detail.posterUrl ?: detail.poster ?: ""
        val type = if (detail.type?.contains("Movie", ignoreCase = true) == true)
            TvType.Movie else TvType.Anime
        val status = when (detail.status) {
            "On-Going"  -> ShowStatus.Ongoing
            "Completed" -> ShowStatus.Completed
            else        -> null
        }

        val episodes = detail.episodes?.map { ep ->
            newEpisode(ep) {
                this.name      = "Episode ${ep.epNumber}"
                this.episode   = ep.epNumber
                this.data      = mapper.writeValueAsString(ep)
                this.posterUrl = poster
            }
        }?.sortedByDescending { it.episode } ?: emptyList()

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            this.posterUrl  = poster
            this.plot       = detail.synopsis
            this.tags       = detail.genres
            this.showStatus = status
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("YunshanID", "=== loadLinks dipanggil ===")
        Log.d("YunshanID", "data: ${data.take(200)}")

        val ep = parseJson<Episode>(data)
        Log.d("YunshanID", "Episode ID=${ep.id}, epNumber=${ep.epNumber}")

        val allUrls = mutableSetOf<String>()

        ep.videoUrl?.let {
            Log.d("YunshanID", "videoUrl: $it")
            allUrls.add(it)
        }
        ep.servers?.forEach { server ->
            Log.d("YunshanID", "server: name=${server.name}, url=${server.url}, embed=${server.embedUrl}")
            server.url?.let { allUrls.add(it) }
            server.embedUrl?.let { allUrls.add(it) }
        }
        ep.downloads?.forEach { dl ->
            Log.d("YunshanID", "download: res=${dl.resolution}, url=${dl.url}")
            dl.url?.let { allUrls.add(it) }
        }

        Log.d("YunshanID", "Total URL dikumpulkan: ${allUrls.size}")

        if (allUrls.isEmpty()) {
            Log.e("YunshanID", "Tidak ada URL sama sekali! Cek API response")
            return false
        }

        allUrls.forEach { url ->
            Log.d("YunshanID", "loadExtractor -> $url")
            loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }

    // ─── Data classes ────────────────────────────────────────────

    private fun DonghuaResponse.toSearchResponse(): SearchResponse {
        val type = if (this.type?.contains("Movie", ignoreCase = true) == true)
            TvType.Movie else TvType.Anime
        val tag = when (status) {
            "Completed" -> " (Completed)"
            "On-Going"  -> " (Ongoing)"
            else        -> ""
        }
        return newAnimeSearchResponse("${this.title}$tag", "$mainUrl/api/donghua/${this.id}", type) {
            this.posterUrl = this@toSearchResponse.posterUrl ?: this@toSearchResponse.poster
            addSub(latestEp ?: 0)
        }
    }

    data class DonghuaResponse(
        @JsonProperty("id")          val id: Int?,
        @JsonProperty("title")       val title: String?,
        @JsonProperty("poster_url")  val posterUrl: String?,
        @JsonProperty("poster")      val poster: String?,
        @JsonProperty("type")        val type: String?,
        @JsonProperty("status")      val status: String?,
        @JsonProperty("latest_ep")   val latestEp: Int?,
        @JsonProperty("last_update") val lastUpdate: String?,
        @JsonProperty("view_count")  val viewCount: Int?
    )

    data class DonghuaDetailResponse(
        @JsonProperty("id")         val id: Int?,
        @JsonProperty("title")      val title: String?,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("poster")     val poster: String?,
        @JsonProperty("synopsis")   val synopsis: String?,
        @JsonProperty("type")       val type: String?,
        @JsonProperty("status")     val status: String?,
        @JsonProperty("genres")     val genres: List<String>?,
        @JsonProperty("rating")     val rating: String?,
        @JsonProperty("episodes")   val episodes: List<Episode>?
    )

    data class Episode(
        @JsonProperty("id")        val id: Int?,
        @JsonProperty("ep_number") val epNumber: Int?,
        @JsonProperty("video_url") val videoUrl: String?,
        @JsonProperty("servers")   val servers: List<Server>?,
        @JsonProperty("downloads") val downloads: List<Download>?
    )

    data class Server(
        @JsonProperty("name")      val name: String?,
        @JsonProperty("url")       val url: String?,
        @JsonProperty("embed_url") val embedUrl: String?
    )

    data class Download(
        @JsonProperty("resolution")   val resolution: String?,
        @JsonProperty("server_name")  val serverName: String?,
        @JsonProperty("url")          val url: String?
    )
}
