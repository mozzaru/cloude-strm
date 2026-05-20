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
        "popular-today"                          to "Populer Hari Ini",
        "rekomendasi"                            to "Rekomendasi",
        "ongoing/"                               to "Series Ongoing",
        "completed/"                             to "Series Completed",
        "drop/"                                  to "Series Drop/Hiatus",
        "anime/?status=&type=Movie&order=update" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (request.data) {
            "" -> getLatestFromHome(page, request.name)
            "popular-today" -> getPopularTodayFromHome(page, request.name)
            "rekomendasi" -> getRekomendasiFromHome(page, request.name)
            else -> {
                val url = when {
                    page == 1 -> "$mainUrl/${request.data}"
                    request.data.contains("?") -> "$mainUrl/${request.data}&page=$page"
                    else -> "$mainUrl/${request.data}page/$page/"
                }
                val document = app.get(url, headers = browserHeaders).document
                // Kategori pages use div.listupd > article.bs
                val items = document.select("div.listupd > article.bs").mapNotNull { it.toSearchResult() }
                val hasNext = document.selectFirst("div.hpage a.r") != null
                newHomePageResponse(
                    list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
                    hasNext = hasNext
                )
            }
        }
    }

    private suspend fun getLatestFromHome(page: Int, name: String): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url, headers = browserHeaders).document
        // Latest releases section: div.bixbox containing div.releases.latesthome
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

    /**
     * "Populer Hari Ini" section:
     * HTML: div.bixbox > div.releases.hothome > h2 "Terpopuler Hari Ini"
     *       div.listupd.popularslider > div.popconslide > article.bs
     * Only available on page 1 (home page).
     */
    private suspend fun getPopularTodayFromHome(page: Int, name: String): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(
                list = HomePageList(name = name, list = emptyList(), isHorizontalImages = false),
                hasNext = false
            )
        }
        val document = app.get(mainUrl, headers = browserHeaders).document
        val section = document.select("div.bixbox").firstOrNull { box ->
            box.selectFirst("div.releases.hothome") != null
        }
        val items = section
            ?.select("div.listupd.popularslider article.bs")
            ?.mapNotNull { it.toEpisodeSearchResult() }
            ?: emptyList()
        return newHomePageResponse(
            list = HomePageList(name = name, list = items, isHorizontalImages = false),
            hasNext = false
        )
    }

    /**
     * "Rekomendasi" section:
     * HTML: div.bixbox > div.releases > h3 "Rekomendasi"
     *       div.series-gen > div.listupd > div.tab-pane > article.bs
     * Only available on page 1 (home page).
     */
    private suspend fun getRekomendasiFromHome(page: Int, name: String): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(
                list = HomePageList(name = name, list = emptyList(), isHorizontalImages = false),
                hasNext = false
            )
        }
        val document = app.get(mainUrl, headers = browserHeaders).document
        val section = document.select("div.bixbox").firstOrNull { box ->
            box.selectFirst("div.releases h3")?.text()?.contains("Rekomendasi", ignoreCase = true) == true
        }
        val items = section
            ?.select("div.series-gen article.bs")
            ?.mapNotNull { it.toSearchResult() }
            ?: emptyList()
        return newHomePageResponse(
            list = HomePageList(name = name, list = items, isHorizontalImages = false),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url, headers = browserHeaders).document
        return document.select("div.listupd > article.bs").mapNotNull { it.toSearchResult() }
    }

    /**
     * Converts a series card (article.bs) to SearchResponse.
     * Extracts: title, href, poster, type, status, and sub label from thumbnail badges.
     *
     * HTML structure:
     *   div.bsx > a[href, title]
     *     div.limit
     *       div.status (Ongoing/Completed/Hiatus) — optional
     *       div.typez (Donghua/Movie/ONA)
     *       div.bt
     *         span.epx (Ep 605 / Completed / Ongoing / Movie)
     *         span.sb (Sub)
     *       img
     *     div.tt (series title)
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("div.bsx > a") ?: return null
        val rawTitle = aTag.attr("title").ifBlank { aTag.text() }
        val href = fixUrl(aTag.attr("href"))
        val posterUrl = extractPoster(aTag)
        val seriesTitle = selectFirst("div.tt")?.ownText()?.trim()
            .takeIf { !it.isNullOrBlank() } ?: rawTitle
        val typeText = selectFirst(".typez")?.text() ?: ""
        val type = if (typeText.contains("movie", ignoreCase = true)) TvType.Movie else TvType.Anime
        return newMovieSearchResponse(seriesTitle, href, type) {
            this.posterUrl = posterUrl
        }
    }

    /**
     * Converts an episode card (from "Rilisan Terbaru" or "Populer Hari Ini") to SearchResponse.
     * The href points to an episode URL like /series-name-episode-N-subtitle-indonesia/.
     * We redirect it to the series URL so CloudStream can load the full series.
     */
    private fun Element.toEpisodeSearchResult(): SearchResponse? {
        val aTag = selectFirst("div.bsx > a") ?: return null
        val episodeHref = fixUrl(aTag.attr("href"))
        val posterUrl = extractPoster(aTag)
        val seriesTitle = selectFirst("div.tt")?.ownText()?.trim()
            .takeIf { !it.isNullOrBlank() }
            ?: aTag.attr("title").ifBlank { aTag.text() }
        val typeText = selectFirst(".typez")?.text() ?: ""
        val type = if (typeText.contains("movie", ignoreCase = true)) TvType.Movie else TvType.Anime
        // Use episode URL directly — load() will handle redirect to series
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
     * Parses episode span text formats from the episode list panel:
     * Format 1: "Eps 605 - Empat Teknik Pedang - May 15, 2026"
     * Format 2: "Eps 605 - May 15, 2026"
     * Format 3: "Eps Niat Pedang Void - September 17, 2021" (no number — bug in source)
     * Format 4: "Eps - July 18, 2022" (empty number)
     *
     * Returns Pair(episodeNumber, episodeTitle)
     * episodeTitle is ONLY the theme/subtitle — NOT prefixed with episode number.
     */
    private fun parseEpisodeTitleFromSpan(spanText: String): Pair<Int?, String> {
        val parts = spanText.split(" - ")
        val epsPart = parts.getOrNull(0)?.trim() ?: ""
        // Extract the number from "Eps 605" or "Eps 101" etc.
        val epNum = Regex("\\d+").find(epsPart)?.value?.toIntOrNull()

        val dateRegex = Regex(
            "^(January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d"
        )
        val secondPart = parts.getOrNull(1)?.trim()
        // Second part is the theme only if it doesn't look like a date
        val epTheme = if (secondPart != null && !dateRegex.containsMatchIn(secondPart)) {
            secondPart
        } else null

        val title = epTheme?.ifBlank { null } ?: ""
        return Pair(epNum, title)
    }

    override suspend fun load(url: String): LoadResponse {
        val isEpisodeUrl = url.contains("-episode-") || url.contains("-subtitle-indonesia")

        // Derive the series (parent) URL from an episode URL
        val seriesUrl = if (isEpisodeUrl) {
            // Try removing episode slug patterns like "-episode-605-subtitle-indonesia/"
            url.replace(Regex("-episode-[^/]+/?$"), "/")
                .replace(Regex("-subtitle-indonesia/?$"), "/")
        } else url

        if (isEpisodeUrl) {
            // Load from the episode page which embeds the full episode list
            val epDoc = app.get(fixUrl(url), headers = browserHeaders).document

            // Title: strip episode number suffix
            val title = epDoc.selectFirst("h1.entry-title")?.text()
                ?.replace(Regex("\\s*Episode\\s+\\d+.*", RegexOption.IGNORE_CASE), "")?.trim()
                ?: epDoc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.replace(Regex("\\s*Episode\\s+\\d+.*", RegexOption.IGNORE_CASE), "")?.trim()
                ?: ""

            val poster = epDoc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: epDoc.selectFirst("div.ime > img")?.attr("src")
                ?: ""
            val description = epDoc.selectFirst("div.entry-content")?.text()?.trim()

            // Genre from series info (single-info panel)
            val genres = epDoc.select("div.genxed a").map { it.text().trim() }

            // Status from series info panel
            val statusText = epDoc.select("div.spe span").firstOrNull { span ->
                span.selectFirst("b")?.text()?.contains("Status", ignoreCase = true) == true
            }?.ownText()?.trim()

            // Episodes from the embedded episode list panel (div.episodelist ul li)
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

            // Derive clean series URL from the headlist link
            val cleanSeriesUrl = epDoc.selectFirst("div.headlist a")?.attr("href")
                ?.let { fixUrl(it) } ?: seriesUrl

            return newTvSeriesLoadResponse(title, cleanSeriesUrl, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
            }
        }

        // Load from series/movie page
        val document = app.get(seriesUrl, headers = browserHeaders).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("div.ime > img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: ""
        val description = document.selectFirst("div.entry-content, div.desc")?.text()?.trim()

        // Genre
        val genres = document.select("div.genxed a").map { it.text().trim() }

        // Status
        val statusText = document.select("div.spe span").firstOrNull { span ->
            span.selectFirst("b")?.text()?.contains("Status", ignoreCase = true) == true
        }?.ownText()?.trim()

        val showStatus = when {
            statusText?.contains("Ongoing", ignoreCase = true) == true -> ShowStatus.Ongoing
            statusText?.contains("Completed", ignoreCase = true) == true -> ShowStatus.Completed
            else -> null
        }

        // Episode list from series page (div.eplister ul li)
        val episodeList = document.select("div.eplister ul li")
        val isSeries = episodeList.isNotEmpty()
        val tvType = if (isSeries) TvType.Anime else TvType.Movie

        val episodes = if (isSeries) {
            episodeList.mapNotNull { li ->
                val a = li.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrl(a.attr("href"))

                // epl-num contains text like "605" or "Episode 605" — extract only digits
                val epNumRaw = li.selectFirst("div.epl-num")?.text()?.trim() ?: ""
                // Use LAST number to avoid false matches (e.g. "Season 2 Episode 605" → 605)
                val epNum = Regex("\\d+").findAll(epNumRaw).lastOrNull()?.value?.toIntOrNull()

                // epl-title is ONLY the theme title — no episode number prefix
                val epTitle = li.selectFirst("div.epl-title")?.text()?.trim()?.ifBlank { null }

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
            // Movie: decode Base64 iframe from the first server option
            val firstOption = document.selectFirst(".mobius option[value]")
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
            this.tags = genres
            this.showStatus = showStatus
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = browserHeaders).document
        document.select(".mobius option[value]").forEach { server ->
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
            } catch (e: Exception) {
                // Silently skip failed extractions
            }
        }
        return true
    }
}
