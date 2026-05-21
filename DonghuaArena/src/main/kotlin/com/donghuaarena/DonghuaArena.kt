package com.donghuaarena

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class DonghuaArena : MainAPI() {
    override var mainUrl = "https://donghuaarena.site"
    override var name = "Donghua Arena"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "api/donghuas" to "Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = app.get("$mainUrl/${request.data}").parsed<List<DonghuaItem>>()
        val searchResponses = items.map { it.toSearchResponse() }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = searchResponses, isHorizontalImages = false),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val items = app.get("$mainUrl/api/donghuas").parsed<List<DonghuaItem>>()
        return items.filter { it.title?.contains(query, ignoreCase = true) == true ||
                it.description?.contains(query, ignoreCase = true) == true }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.removePrefix("$mainUrl/anime/")
        val items = app.get("$mainUrl/api/donghuas").parsed<List<DonghuaItem>>()
        val item = items.find { it.id == id } ?: throw ErrorLoadingException("Anime not found")

        val episodesRaw = app.get("$mainUrl/api/episodes?donghua_id=$id").parsed<List<EpisodeItem>>()
        val episodes = episodesRaw
            .sortedBy { it.episodeNumber }
            .map { ep ->
                newEpisode(ep.id) {
                    this.name = "Episode ${ep.episodeNumber}"
                    this.episode = ep.episodeNumber
                    this.data = ep.videoUrl ?: ""
                }
            }

        return newTvSeriesLoadResponse(item.title ?: "", url, TvType.Anime, episodes) {
            this.posterUrl = item.posterUrl
            this.plot = item.description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data is the videoUrl from episode or the episode ID to fetch servers
        if (data.startsWith("http")) {
            loadExtractor(data, "$mainUrl/", subtitleCallback, callback)
        }

        // Also fetch from servers API
        val servers = app.get("$mainUrl/api/servers?episode_id=$data").parsed<List<ServerItem>>()
        servers.forEach { server ->
            server.url?.let {
                loadExtractor(it, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }

    private fun DonghuaItem.toSearchResponse(): SearchResponse {
        val statusLabel = if (status.equals("End", true) || status.equals("Completed", true)) " (Completed)" else ""
        val title = (this.title ?: "") + statusLabel
        val href = "$mainUrl/anime/${this.id}"
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = this@toSearchResponse.posterUrl
            addSub(this@toSearchResponse.eps)
        }
    }

    data class DonghuaItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("rating") val rating: Double? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("eps") val eps: Int? = null
    )

    data class EpisodeItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("donghua_id") val donghuaId: String? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("video_url") val videoUrl: String? = null
    )

    data class ServerItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("episode_id") val episodeId: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("url") val url: String? = null
    )
}
