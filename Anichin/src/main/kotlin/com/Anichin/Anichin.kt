package com.Anichin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Anichin : MainAPI() {
    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        ""                                      to "Rilisan Terbaru",
        "ongoing/"                              to "Series Ongoing",
        "completed/"                            to "Series Completed",
        "drop/"                                 to "Series Drop/Hiatus",
        "anime/?status=&type=Movie&order=update" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "") {
            return getLatestFromHome(page, request.name)
        }

        val url = when {
            page == 1 -> "$mainUrl/${request.data}"
            request.data.contains("?") -> "$mainUrl/${request.data}&page=$page"
            else -> "$mainUrl/${request.data}page/$page/"
        }

        val document = app.get(url).document

        val items = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
        val hasNext = document.selectFirst("div.hpage a.r") != null

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private suspend fun getLatestFromHome(page: Int, name: String): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document

        val latestSection = document.selectFirst("div.bixbox:has(div.releases.latesthome)")

        val items = latestSection
            ?.select("div.listupd article")
            ?.mapNotNull { it.toEpisodeSearchResult() }
            ?: emptyList()

        val hasNext = document.selectFirst("div.hpage a.r") != null

        return newHomePageResponse(
            list = HomePageList(name = name, list = items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("div.bsx > a") ?: return null
        val rawTitle = aTag.attr("title").ifBlank { aTag.text() }
        val href = fixUrl(aTag.attr("href"))
        val img = aTag.selectFirst("img")

        val posterUrlRaw = img?.run {
            attr("src").ifBlank { attr("data-src") }.ifBlank { attr("data-lazy-src") }
        }.orEmpty()
        val posterUrlFixed = if (posterUrlRaw.startsWith("//")) "https:$posterUrlRaw" else posterUrlRaw
        val posterUrl = fixUrlNull(posterUrlFixed)

        val seriesTitle = selectFirst("div.tt")?.ownText()?.trim()
            ?.ifBlank { rawTitle } ?: rawTitle

        val type = if (selectFirst(".typez")?.text()?.contains("movie", ignoreCase = true) == true)
            TvType.Movie else TvType.Anime

        return newMovieSearchResponse(seriesTitle, href, type) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toEpisodeSearchResult(): SearchResponse? {
        val aTag = selectFirst("div.bsx > a") ?: return null
        val rawTitle = aTag.attr("title").ifBlank { aTag.text() }
        val episodeHref = fixUrl(aTag.attr("href"))
        val img = aTag.selectFirst("img")

        val posterUrlRaw = img?.run {
            attr("src").ifBlank { attr("data-src") }.ifBlank { attr("data-lazy-src") }
        }.orEmpty()
        val posterUrlFixed = if (posterUrlRaw.startsWith("//")) "https:$posterUrlRaw" else posterUrlRaw
        val posterUrl = fixUrlNull(posterUrlFixed)

        val seriesTitle = selectFirst("div.tt")?.ownText()?.trim()
            ?.ifBlank { rawTitle } ?: rawTitle

        val type = if (selectFirst(".typez")?.text()?.contains("movie", ignoreCase = true) == true)
            TvType.Movie else TvType.Anime

        return newMovieSearchResponse(seriesTitle, episodeHref, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val seriesUrl = if (url.contains("-episode-")) {
            url.replace(Regex("-episode-\\d+[^/]*/"), "/")
        } else url

        val document = app.get(seriesUrl).document
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
                val epHref = fixUrl(a.attr("href"))
                val epTitle = li.selectFirst("div.epl-title")?.text()?.trim()
                val epNumText = li.selectFirst("div.epl-num")?.text()?.trim()
                val epNum = epNumText?.toIntOrNull()
                val epPoster = li.selectFirst("div.epl-image img")?.run {
                    attr("src").ifBlank { attr("data-src") }
                }.orEmpty()

                newEpisode(epHref) {
                    this.name = epTitle ?: "Episode $epNum"
                    this.episode = epNum
                    this.posterUrl = epPoster.ifBlank { poster }
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

            if (playUrl == null) playUrl = seriesUrl

            listOf(newEpisode(playUrl) {
                name = "Movie"
                posterUrl = poster
            })
        }

        return newTvSeriesLoadResponse(title, seriesUrl, tvType, episodes) {
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
        val document = app.get(data).document

        document.select(".mobius option").forEach { server ->
            val base64 = server.attr("value").trim()
            if (base64.isBlank()) return@forEach

            try {
                val decoded = base64Decode(base64)
                val iframe = Jsoup.parse(decoded).selectFirst("iframe")
                val iframeSrc = iframe?.attr("src")?.ifBlank { iframe.attr("data-src") }?.trim()
                if (!iframeSrc.isNullOrBlank()) {
                    val finalUrl = if (iframeSrc.startsWith("http")) iframeSrc else "https:$iframeSrc"
                    println("🎯 [Anichin] Trying to extract: $finalUrl")
                    loadExtractor(finalUrl, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                println("❌ [Anichin] Error decoding Base64 or extracting: ${e.message}")
            }
        }

        return true
    }
}
