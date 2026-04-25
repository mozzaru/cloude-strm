package com.donghub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Donghub : MainAPI() {
    override var mainUrl = "https://donghub.vip"
    override var name = "Donghub"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    // ----------------------------------------------------------------
    private val cfHeaders = mapOf(
        "User-Agent"                to "Mozilla/5.0 (Linux; Android 11; RMX2103) " +
                                       "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                       "Chrome/147.0.7699.1 Mobile Safari/537.36",
        "Accept"                    to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language"           to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Accept-Encoding"           to "gzip, deflate, br",
        "Referer"                   to "https://donghub.vip/",
        "DNT"                       to "1",
        "Connection"                to "keep-alive",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest"            to "document",
        "Sec-Fetch-Mode"            to "navigate",
        "Sec-Fetch-Site"            to "same-origin",
        "Sec-Fetch-User"            to "?1",
    )

    override val mainPage = mainPageOf(
        "anime/?order=update"                    to "Rilisan Terbaru",
        "anime/?status=ongoing&order=update"     to "Series Ongoing",
        "anime/?status=completed&order=update"   to "Series Completed",
        "anime/?status=hiatus&order=update"      to "Series Drop/Hiatus",
        "anime/?type=movie&order=update"         to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val allItems = mutableListOf<SearchResponse>()
        val maxPages = if (request.name in listOf("Rilisan Terbaru", "Series Completed")) 3 else 1
        var hasNext = false

        for (i in 1..maxPages) {
            val document = app.get(
                "$mainUrl/${request.data}&page=$i",
                headers = cfHeaders
            ).document

            val items = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
            allItems.addAll(items)

            val lastPage = document.select("a.page-numbers").lastOrNull()?.text()?.toIntOrNull()
            if (lastPage != null && i < lastPage) hasNext = true
        }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = allItems, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("div.bsx > a") ?: return null
        val rawTitle = aTag.attr("title").ifBlank { aTag.text() }
        val href = fixUrl(aTag.attr("href"))
        val img = aTag.selectFirst("img")

        val posterUrlRaw = img?.run {
            attr("data-src").ifBlank { attr("src") }.ifBlank { attr("data-lazy-src") }
        }.orEmpty()

        val posterUrlFixed = if (posterUrlRaw.startsWith("//")) "https:$posterUrlRaw" else posterUrlRaw
        val type = if (href.contains("/movie/")) TvType.Movie else TvType.Anime
        val statusLabel = selectFirst("div.bt span")?.text()?.lowercase().orEmpty()
        val title = if ("complete" in statusLabel) "$rawTitle (Completed)" else rawTitle

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = fixUrlNull(posterUrlFixed)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query", headers = cfHeaders).document
            .select("div.listupd > article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = cfHeaders).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        var poster = document.selectFirst("div.ime > img")?.attr("src").orEmpty()
        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        }
        val description = document.selectFirst("div.entry-content")?.text()?.trim()

        val episodeList = document.select("div.episodelist > ul > li")
            .ifEmpty { document.select("div.eplister > ul > li") }

        val isSeries = episodeList.isNotEmpty()

        val episodes = if (isSeries) {
            episodeList.mapIndexed { index, el ->
                val epHref = el.selectFirst("a")?.attr("href").orEmpty()
                val epName = el.select("a span")?.text()
                    ?.substringAfter("-")?.substringBeforeLast("-")?.trim()
                newEpisode(epHref) {
                    this.name = if (!epName.isNullOrBlank()) epName else "Episode ${index + 1}"
                    this.posterUrl = el.selectFirst("a img")?.attr("src").orEmpty()
                }
            }.reversed()
        } else {
            val playUrl = decodeIframeSrc(
                document.selectFirst(".mobius option[value]")?.attr("value")?.trim()
            ) ?: url

            listOf(newEpisode(playUrl) { name = "Movie"; posterUrl = poster })
        }

        return newTvSeriesLoadResponse(title, url, if (isSeries) TvType.Anime else TvType.Movie, episodes) {
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
        val document = app.get(data, headers = cfHeaders).document

        document.select(".mobius option[value]").forEach { option ->
            val base64 = option.attr("value").trim()
            if (base64.isBlank()) return@forEach

            val serverUrl = decodeIframeSrc(base64) ?: return@forEach
            val serverName = option.text().trim()
            println("🎯 [Donghub] Server: $serverName → $serverUrl")

            loadExtractor(serverUrl, data, subtitleCallback, callback)
        }

        return true
    }

    // Decode base64 option value → ambil src dari iframe
    private fun decodeIframeSrc(base64: String?): String? {
        if (base64.isNullOrBlank()) return null
        return try {
            val decoded = base64Decode(base64)
            val src = Jsoup.parse(decoded).selectFirst("iframe")
                ?.attr("src")?.trim() ?: return null
            if (src.startsWith("http")) src else "https:$src"
        } catch (_: Exception) { null }
    }
}
