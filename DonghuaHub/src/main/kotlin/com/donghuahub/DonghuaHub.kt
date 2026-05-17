package com.donghuahub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty

class DonghuaHub : MainAPI() {
    override var mainUrl = "https://donghub.vip"
    override var name = "DonghuaHub"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    private val sites = mapOf(
        "anichin" to "https://anichin.moe",
        "donghub" to "https://donghub.vip",
        "yunshan" to "https://yunshanid.site",
        "animexin" to "https://animexin.dev",
        "lucifer" to "https://luciferdonghua.in"
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )

    override val mainPage = mainPageOf(
        "anichin" to "[Anichin] Rilisan Terbaru",
        "donghub" to "[Donghub] Rilisan Terbaru",
        "yunshan" to "[YunshanID] Rilisan Terbaru",
        "animexin" to "[Animexin] Rilisan Terbaru",
        "lucifer" to "[Lucifer] Latest Release"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = try {
            when (request.data) {
                "anichin" -> parseAnimeStreamPage(sites["anichin"]!!, sites["anichin"]!!)
                "donghub" -> parseAnimeStreamPage(sites["donghub"]!!, sites["donghub"]!!)
                "yunshan" -> parseYunshanPage()
                "animexin" -> parseAnimeStreamPage(sites["animexin"]!!, sites["animexin"]!!)
                "lucifer" -> parseAnimeStreamPage(sites["lucifer"]!!, sites["lucifer"]!!)
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        sites.forEach { (key, baseUrl) ->
            try {
                if (key == "yunshan") {
                    val response = app.get("$baseUrl/api/donghuas", headers = browserHeaders).text
                    val parsed = AppUtils.tryParseJson<List<DonghuaResponse>>(response)
                    parsed?.filter { it.title.contains(query, ignoreCase = true) }?.forEach {
                        results.add(it.toSearchResponse())
                    }
                } else {
                    val document = app.get("$baseUrl/?s=$query", headers = browserHeaders).document
                    document.select("div.listupd article").forEach {
                        it.toSearchResult(baseUrl)?.let { res -> results.add(res) }
                    }
                }
            } catch (_: Exception) {}
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        return when {
            url.contains("yunshanid.site") || !url.startsWith("http") -> loadYunshan(url)
            else -> loadAnimeStream(url)
        }
    }

    private suspend fun loadYunshan(url: String): LoadResponse {
        val id = url.substringAfterLast("/")
        val response = app.get("${sites["yunshan"]}/api/donghua/$id", headers = browserHeaders).text
        val donghua = AppUtils.tryParseJson<DonghuaDetailResponse>(response)
            ?: throw ErrorLoadingException("Failed to parse Yunshan detail")

        val episodes = donghua.episodes.map { ep ->
            newEpisode(ep.videoUrl) {
                this.episode = ep.epNumber
                this.name = "Episode ${ep.epNumber}"
            }
        }.sortedByDescending { it.episode }

        return newAnimeLoadResponse(donghua.title, url, TvType.Anime, false) {
            this.posterUrl = donghua.posterUrl
            this.plot = donghua.synopsis
            this.tags = donghua.genres
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun String.getSiteKey(): String? {
        return sites.entries.find { this.contains(it.value.substringAfter("://")) }?.key
    }

    private suspend fun loadAnimeStream(url: String): LoadResponse {
        val document = app.get(url, headers = browserHeaders).document
        val siteKey = url.getSiteKey()
        val baseUrl = (if (siteKey != null) sites[siteKey] else null) ?: url.substringBeforeLast("/")

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        var poster = document.selectFirst("div.ime > img")?.attr("src").orEmpty()
        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        }
        val description = document.selectFirst("div.entry-content")?.text()?.trim()

        val episodeList = document.select("div.eplister ul li")
        val isSeries = episodeList.isNotEmpty()
        val tvType = if (isSeries) TvType.Anime else TvType.Movie

        val episodes = if (isSeries) {
            episodeList.mapNotNull { li ->
                val a = li.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrl(a.attr("href"), baseUrl)
                val epNumRaw = li.selectFirst("div.epl-num")?.text()?.trim() ?: ""
                val epNum = Regex("\\d+").findAll(epNumRaw).lastOrNull()?.value?.toIntOrNull()
                val epTitle = li.selectFirst("div.epl-title")?.text()?.trim()
                newEpisode(epHref) {
                    this.name = epTitle
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }.reversed()
        } else {
            val firstOption = document.selectFirst(".mobius option")
            val base64 = firstOption?.attr("value")?.trim()
            var playUrl: String? = null
            if (!base64.isNullOrBlank()) {
                try {
                    val decoded = base64Decode(base64)
                    val iframe = Jsoup.parse(decoded).selectFirst("iframe")
                    val rawSrc = iframe?.attr("src")
                    if (!rawSrc.isNullOrBlank()) {
                        playUrl = if (rawSrc.startsWith("http")) rawSrc else "https:$rawSrc"
                    }
                } catch (_: Exception) {}
            }
            if (playUrl == null) playUrl = url
            listOf(newEpisode(playUrl) {
                name = "Movie"
                posterUrl = poster
            })
        }

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("http") && sites.values.none { data.contains(it.substringAfter("://")) }) {
            loadExtractor(data, data, subtitleCallback, callback)
            return true
        }

        val document = try { app.get(data, headers = browserHeaders).document } catch (e: Exception) { return false }
        document.select(".mobius option").forEach { server ->
            val base64 = server.attr("value").trim()
            if (base64.isBlank()) return@forEach
            try {
                val decoded = base64Decode(base64)
                val iframe = Jsoup.parse(decoded).selectFirst("iframe")
                val iframeSrc = iframe?.attr("src")?.ifBlank { iframe.attr("data-src") }?.trim()
                if (!iframeSrc.isNullOrBlank()) {
                    val finalUrl = if (iframeSrc.startsWith("http")) iframeSrc else "https:$iframeSrc"
                    loadExtractor(finalUrl, data, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }
        return true
    }

    private fun Element.toSearchResult(baseUrl: String): SearchResponse? {
        val aTag = selectFirst("div.bsx > a") ?: return null
        val rawTitle = (aTag.attr("title").takeIf { it.isNotBlank() } ?: selectFirst("div.tt")?.ownText() ?: aTag.text()).trim()
        val href = fixUrl(aTag.attr("href"), baseUrl)

        val img = aTag.selectFirst("img")
        val posterUrlRaw = img?.run {
            attr("src").ifBlank { attr("data-src") }.ifBlank { attr("data-lazy-src") }
        }.orEmpty()
        val posterUrl = fixUrlNull(if (posterUrlRaw.startsWith("//")) "https:$posterUrlRaw" else posterUrlRaw)

        val typeLabel = selectFirst(".typez")?.text()?.lowercase().orEmpty()
        val type = if (typeLabel.contains("movie")) TvType.Movie else TvType.Anime

        return newAnimeSearchResponse(rawTitle, href, type) {
            this.posterUrl = posterUrl
        }
    }

    private fun fixUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            else -> "$baseUrl$url"
        }
    }

    fun DonghuaResponse.toSearchResponse(): SearchResponse {
        val titleWithStatus = if (status == "Completed") "$title (Completed)" else if (status == "On-Going") "$title (Ongoing)" else title
        return newAnimeSearchResponse(titleWithStatus, id.toString(), TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(latestEp)
        }
    }

    private suspend fun parseYunshanPage(): List<SearchResponse> {
        val response = app.get("${sites["yunshan"]}/api/donghuas", headers = browserHeaders).text
        val parsed = AppUtils.tryParseJson<List<DonghuaResponse>>(response)
        return parsed?.map { it.toSearchResponse() } ?: emptyList()
    }

    private suspend fun parseAnimeStreamPage(url: String, baseUrl: String): List<SearchResponse> {
        val document = app.get(url, headers = browserHeaders).document
        val latestSection = document.select("div.bixbox").firstOrNull { box ->
            box.selectFirst("div.releases.latesthome") != null
        }
        val items = latestSection?.select("article.bs")?.mapNotNull { it.toSearchResult(baseUrl) }
            ?: document.select("div.listupd > article").mapNotNull { it.toSearchResult(baseUrl) }

        return items.distinctBy { it.url }
    }
}

data class DonghuaResponse(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title") val title: String,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("status") val status: String?,
    @JsonProperty("latest_ep") val latestEp: Int?
)

data class DonghuaDetailResponse(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title") val title: String,
    @JsonProperty("synopsis") val synopsis: String?,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("rating") val rating: Double,
    @JsonProperty("genres") val genres: List<String>?,
    @JsonProperty("episodes") val episodes: List<EpisodeResponse>
)

data class EpisodeResponse(
    @JsonProperty("id") val id: Int,
    @JsonProperty("ep_number") val epNumber: Int,
    @JsonProperty("video_url") val videoUrl: String,
)
