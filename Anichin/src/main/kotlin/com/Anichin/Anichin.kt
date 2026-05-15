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

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Upgrade-Insecure-Requests" to "1",
    )

    override val mainPage = mainPageOf(
        ""                                       to "Rilisan Terbaru",
        "ongoing/"                               to "Series Ongoing",
        "completed/"                             to "Series Completed",
        "drop/"                                  to "Series Drop/Hiatus",
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
        val document = app.get(url, headers = browserHeaders).document
        val items = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
        val hasNext = document.selectFirst("div.hpage a.r") != null
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private suspend fun getLatestFromHome(page: Int, name: String): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url, headers = browserHeaders).document
        val latestSection = document.select("div.bixbox").firstOrNull { box ->
            box.selectFirst("div.releases.latesthome") != null
        }
        val items = latestSection
            ?.select("article.bs")
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
        val posterUrl = extractPoster(aTag)
        val seriesTitle = selectFirst("div.tt")?.ownText()?.trim()
            .takeIf { !it.isNullOrBlank() } ?: rawTitle
        val type = if (selectFirst(".typez")?.text()?.contains("movie", ignoreCase = true) == true)
            TvType.Movie else TvType.Anime
        return newMovieSearchResponse(seriesTitle, href, type) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toEpisodeSearchResult(): SearchResponse? {
        val aTag = selectFirst("div.bsx > a") ?: return null
        val episodeHref = fixUrl(aTag.attr("href"))
        val posterUrl = extractPoster(aTag)
        val seriesTitle = selectFirst("div.tt")?.ownText()?.trim()
            .takeIf { !it.isNullOrBlank() }
            ?: aTag.attr("title").ifBlank { aTag.text() }
        val type = if (selectFirst(".typez")?.text()?.contains("movie", ignoreCase = true) == true)
            TvType.Movie else TvType.Anime
        return newMovieSearchResponse(seriesTitle, episodeHref, type) {
            this.posterUrl = posterUrl
        }
    }

    private fun extractPoster(aTag: Element): String? {
        val img = aTag.selectFirst("img") ?: return null
        val raw = img.attr("src").ifBlank { img.attr("data-src") }.ifBlank { img.attr("data-lazy-src") }
        val fixed = if (raw.startsWith("//")) "https:$raw" else raw
        return fixUrlNull(fixed)
    }

    /**
     * Parse span text format: "Eps 605 - Empat Teknik Pedang - May 15, 2026"
     * Returns Pair(episodeNumber, episodeTitle)
     * episodeTitle only contains the theme/subtitle, NOT the episode number.
     */
    private fun parseEpisodeTitleFromSpan(spanText: String): Pair<Int?, String> {
        // Format: "Eps NNN - Theme Title - Date" or "Eps NNN - Date"
        val parts = spanText.split(" - ")
        val epsPart = parts.getOrNull(0)?.trim() ?: ""
        val epNum = Regex("\\d+").find(epsPart)?.value?.toIntOrNull()

        // The theme is the second part, but only if it's not a date
        // Dates look like "May 15, 2026" or "April 2026" etc.
        val dateRegex = Regex("^(January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d")
        val secondPart = parts.getOrNull(1)?.trim()
        val epTheme = if (secondPart != null && !dateRegex.containsMatchIn(secondPart)) {
            secondPart
        } else null

        // Title is ONLY the theme (no episode number prefix to avoid duplication)
        val title = epTheme?.ifBlank { null } ?: ""
        return Pair(epNum, title)
    }

    override suspend fun load(url: String): LoadResponse {
        val isEpisodeUrl = url.contains("-episode-")

        val seriesUrl = if (isEpisodeUrl) {
            url.replace(Regex("-episode-\\d+[^/]*/"), "/")
        } else url

        if (isEpisodeUrl) {
            val epDoc = app.get(fixUrl(url), headers = browserHeaders).document

            val title = epDoc.selectFirst("h1.entry-title")?.text()
                ?.replace(Regex("Episode \\d+.*", RegexOption.IGNORE_CASE), "")?.trim()
                ?: epDoc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.replace(Regex("Episode \\d+.*", RegexOption.IGNORE_CASE), "")?.trim()
                ?: ""

            var poster = epDoc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            val description = epDoc.selectFirst("div.entry-content")?.text()?.trim()

            val episodeLis = epDoc.select("div.episodelist ul li")
            val episodes = episodeLis.mapNotNull { li ->
                val a = li.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrl(a.attr("href"))
                val spanText = li.selectFirst("div.playinfo span")?.text()?.trim() ?: ""
                val (epNum, epTheme) = parseEpisodeTitleFromSpan(spanText)
                val epPoster = li.selectFirst("div.thumbnel img")?.run {
                    attr("src").ifBlank { attr("data-src") }
                }.orEmpty()

                newEpisode(epHref) {
                    this.name = epTheme.ifBlank { null }
                    this.episode = epNum
                    this.posterUrl = epPoster.ifBlank { poster }
                }
            }.reversed()

            return newTvSeriesLoadResponse(title, seriesUrl, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        val document = app.get(seriesUrl, headers = browserHeaders).document
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
                val epNumRaw = li.selectFirst("div.epl-num")?.text()?.trim() ?: ""
                val epNum = Regex("\\d+").findAll(epNumRaw).lastOrNull()?.value?.toIntOrNull()
                // Only use theme title, NOT episode number string
                val epTitle = li.selectFirst("div.epl-title")?.text()?.trim()
                    ?.ifBlank { null }
                val epPoster = li.selectFirst("div.epl-image img")?.run {
                    attr("src").ifBlank { attr("data-src") }
                }.orEmpty()
                newEpisode(epHref) {
                    this.name = epTitle
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
        val document = app.get(data, headers = browserHeaders).document
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
