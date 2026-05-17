package com.donghuahub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
        "all" to "Semua Rilisan Terbaru (Merged)",
        "anichin|anime/?order=update" to "[Anichin] Rilisan Terbaru",
        "donghub|anime/?order=update" to "[Donghub] Rilisan Terbaru",
        "yunshan|api/donghuas" to "[YunshanID] Rilisan Terbaru",
        "animexin|anime/?order=update" to "[Animexin] Rilisan Terbaru",
        "lucifer|anime/?order=update" to "[Lucifer] Latest Release"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "all") {
            val allItems = coroutineScope {
                sites.map { (key, baseUrl) ->
                    async {
                        try {
                            if (key == "yunshan") parseYunshanPage()
                            else parseAnimeStreamPage("$baseUrl/anime/?order=update", baseUrl)
                        } catch (_: Exception) {
                            emptyList<SearchResponse>()
                        }
                    }
                }.awaitAll().flatten().distinctBy { it.url }
            }
            return newHomePageResponse(
                list = HomePageList(name = request.name, list = allItems, isHorizontalImages = false),
                hasNext = false
            )
        }

        val parts = request.data.split("|")
        val siteKey = parts[0]
        val path = parts.getOrNull(1) ?: ""
        val baseUrl = sites[siteKey] ?: return newHomePageResponse(request.name, emptyList())

        val items = mutableListOf<SearchResponse>()
        var hasNext = false

        try {
            if (siteKey == "yunshan") {
                items.addAll(parseYunshanPage())
            } else {
                val url = if (page == 1) "$baseUrl/$path" else {
                    val pagePath = "page/$page/"
                    if (path.contains("?")) {
                        val base = path.substringBefore("?")
                        val query = path.substringAfter("?")
                        "$baseUrl/$base$pagePath?$query"
                    } else {
                        "$baseUrl/$path$pagePath"
                    }
                }
                val document = app.get(url, headers = browserHeaders).document
                items.addAll(document.select("div.listupd article").mapNotNull { it.toSearchResult(baseUrl) })
                hasNext = document.selectFirst("div.hpage a.r, a.next, .next.page-numbers") != null
            }
        } catch (e: Exception) {
            println("❌ [DonghuaHub] Error fetching main page from $siteKey: ${e.message}")
        }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val deferredResults = sites.map { (key, baseUrl) ->
            async {
                try {
                    if (key == "yunshan") {
                        val response = app.get("$baseUrl/api/donghuas", headers = browserHeaders).text
                        val parsed = AppUtils.tryParseJson<List<DonghuaResponse>>(response)
                        parsed?.filter { it.title.contains(query, ignoreCase = true) }?.map {
                            it.toSearchResponse()
                        } ?: emptyList()
                    } else {
                        val document = app.get("$baseUrl/?s=$query", headers = browserHeaders).document
                        document.select("div.listupd article").mapNotNull {
                            it.toSearchResult(baseUrl)
                        }
                    }
                } catch (_: Exception) {
                    emptyList<SearchResponse>()
                }
            }
        }
        deferredResults.awaitAll().flatten().distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        return when {
            url.contains("yunshanid.site") -> loadYunshan(url)
            else -> loadAnimeStream(url)
        }
    }

    private suspend fun loadYunshan(url: String): LoadResponse {
        val id = url.substringAfterLast("/")
        val apiUrl = if (url.contains("/api/")) url else "${sites["yunshan"]}/api/donghua/$id"
        val response = app.get(apiUrl, headers = browserHeaders).text
        val donghua = AppUtils.tryParseJson<DonghuaDetailResponse>(response)
            ?: throw ErrorLoadingException("Failed to parse Yunshan detail")

        val episodes = donghua.episodes.map { ep ->
            newEpisode(ep.videoUrl) {
                this.episode = ep.epNumber
                this.name = "Episode ${ep.epNumber}"
            }
        }.sortedByDescending { it.episode }

        return newTvSeriesLoadResponse(donghua.title, url, TvType.Anime, episodes) {
            this.posterUrl = donghua.posterUrl ?: donghua.poster
            this.plot = donghua.synopsis
            this.tags = donghua.genres
            this.showStatus = when (donghua.status) {
                "On-Going" -> ShowStatus.Ongoing
                "Completed" -> ShowStatus.Completed
                else -> null
            }
        }
    }

    private fun String.getSiteKey(): String? {
        return sites.entries.find { this.contains(it.value.substringAfter("://")) }?.key
    }

    private suspend fun loadAnimeStream(url: String): LoadResponse {
        val initialDoc = app.get(url, headers = browserHeaders).document

        // Handle episode pages by finding the series link
        val seriesLink = initialDoc.selectFirst("div.ts-breadcrumb a[href*=\"/anime/\"], div.ts-breadcrumb a[href*=\"/series/\"], div.breadcrumb a[href*=\"/anime/\"], div.breadcrumb a[href*=\"/series/\"], div.nvs.nvsc a")?.attr("href")
        val isEpisode = url.contains("-episode-")

        val fetchUrl = if (isEpisode && !seriesLink.isNullOrBlank()) fixUrl(seriesLink, url) else url
        val document = if (fetchUrl != url) app.get(fetchUrl, headers = browserHeaders).document else initialDoc

        val siteKey = fetchUrl.getSiteKey()
        val baseUrl = (if (siteKey != null) sites[siteKey] else null) ?: fetchUrl.substringBeforeLast("/")

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        var poster = document.selectFirst("div.ime > img")?.attr("src").orEmpty()
        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        }
        val description = document.selectFirst("div.entry-content")?.text()?.trim()

        val episodeList = document.select("div.eplister ul li, div.episodelist ul li")
        val isSeries = episodeList.isNotEmpty()
        val tvType = if (isSeries) TvType.Anime else TvType.Movie

        val episodes = if (isSeries) {
            episodeList.mapNotNull { li ->
                val a = li.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrl(a.attr("href"), baseUrl)
                val epNumRaw = li.selectFirst("div.epl-num")?.text()?.trim() ?: ""
                val epNum = Regex("\\d+").findAll(epNumRaw).lastOrNull()?.value?.toIntOrNull()
                val epTitle = (li.selectFirst("div.epl-title")?.text() ?: li.selectFirst("div.playinfo span")?.text())?.trim()
                newEpisode(epHref) {
                    this.name = epTitle
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }.reversed()
        } else {
            val firstOption = document.selectFirst(".mobius option, .mirror option")
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

        return newTvSeriesLoadResponse(title, fetchUrl, tvType, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = document.select("span.genre a, div.gencontent a").map { it.text() }
            val statusLabel = (document.selectFirst("div.spe span:contains(Status), div.info-content span:contains(Status)")?.text() ?: "").lowercase()
            this.showStatus = when {
                statusLabel.contains("ongoing") -> ShowStatus.Ongoing
                statusLabel.contains("completed") || statusLabel.contains("tamat") -> ShowStatus.Completed
                else -> null
            }
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
        document.select(".mobius option, .mirror option").forEach { server ->
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
            .replace(Regex("(?i)Subtitle Indonesia|Episode \\d+"), "").trim()
        val href = fixUrl(aTag.attr("href"), baseUrl)

        val img = aTag.selectFirst("img")
        val posterUrlRaw = img?.run {
            attr("src").ifBlank { attr("data-src") }.ifBlank { attr("data-lazy-src") }
        }.orEmpty()
        val posterUrl = fixUrlNull(if (posterUrlRaw.startsWith("//")) "https:$posterUrlRaw" else posterUrlRaw)

        val typeLabel = selectFirst(".typez")?.text()?.lowercase().orEmpty()
        val type = if (typeLabel.contains("movie")) TvType.Movie else TvType.Anime

        val epText = selectFirst("span.epx, span.eggepisode, div.bt span.epx")?.text().orEmpty()
        val epNum = epText.replace(Regex("[^0-9]"), "").toIntOrNull()

        val statusText = (selectFirst("div.status, div.bt span.epx, .status")?.text() ?: "").lowercase()
        val statusTag = when {
            statusText.contains("tamat") || statusText.contains("complete") -> "(Completed)"
            statusText.contains("ongoing") -> "(Ongoing)"
            else -> ""
        }

        val finalTitle = "${rawTitle.replace(statusTag, "").trim()} $statusTag (Sub Indo)".replace(Regex("\\s+"), " ").trim()
        return newAnimeSearchResponse(finalTitle, href, type) {
            this.posterUrl = posterUrl
            addDubStatus(DubStatus.Subbed, epNum)
            addSub(epNum)
        }
    }

    private fun fixUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                val siteBase = baseUrl.substringBefore("/", "https://") + "//" + baseUrl.substringAfter("//").substringBefore("/")
                "$siteBase$url"
            }
            else -> {
                val siteBase = baseUrl.substringBefore("/", "https://") + "//" + baseUrl.substringAfter("//").substringBefore("/")
                "$siteBase/$url".replace("//", "/").replace(":/", "://")
            }
        }
    }

    fun DonghuaResponse.toSearchResponse(): SearchResponse {
        val cleanTitle = title.replace(Regex("(?i)Subtitle Indonesia|Episode \\d+"), "").trim()
        val statusTag = when (status) {
            "Completed" -> "(Completed)"
            "On-Going" -> "(Ongoing)"
            else -> ""
        }
        val absoluteUrl = "${sites["yunshan"]}/api/donghua/$id"
        val finalTitle = "${cleanTitle.replace(statusTag, "").trim()} $statusTag (Sub Indo)".replace(Regex("\\s+"), " ").trim()
        return newAnimeSearchResponse(finalTitle, absoluteUrl, TvType.Anime) {
            this.posterUrl = posterUrl ?: poster
            addDubStatus(DubStatus.Subbed, latestEp)
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
        val items = document.select("div.listupd article").mapNotNull { it.toSearchResult(baseUrl) }
        return items.distinctBy { it.url }
    }
}

data class DonghuaResponse(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title") val title: String,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("status") val status: String?,
    @JsonProperty("latest_ep") val latestEp: Int?
)

data class DonghuaDetailResponse(
    @JsonProperty("id") val id: Int,
    @JsonProperty("title") val title: String,
    @JsonProperty("synopsis") val synopsis: String?,
    @JsonProperty("poster_url") val posterUrl: String?,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("status") val status: String?,
    @JsonProperty("rating") val rating: Double,
    @JsonProperty("genres") val genres: List<String>?,
    @JsonProperty("episodes") val episodes: List<EpisodeResponse>
)

data class EpisodeResponse(
    @JsonProperty("id") val id: Int,
    @JsonProperty("ep_number") val epNumber: Int,
    @JsonProperty("video_url") val videoUrl: String,
)
