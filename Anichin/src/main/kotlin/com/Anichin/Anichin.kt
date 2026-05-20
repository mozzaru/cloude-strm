package com.Anichin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class Anichin : MainAPI() {
    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    private val browserHeaders = mapOf(
        "User-Agent"               to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
        "Accept"                   to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language"          to "id-ID,id;q=0.9",
        "Cache-Control"            to "no-cache",
        "Pragma"                   to "no-cache",
        "Sec-Fetch-Dest"           to "document",
        "Sec-Fetch-Mode"           to "navigate",
        "Sec-Fetch-Site"           to "none",
        "Upgrade-Insecure-Requests" to "1",
    )

    override val mainPage = mainPageOf(
        ""                                       to "Rilisan Terbaru",
        "popular-today"                          to "Populer Hari Ini",
        "rekomendasi"                            to "Rekomendasi",
        "ongoing"                                to "Series Ongoing",
        "completed"                              to "Series Completed",
        "drop"                                   to "Series Drop/Hiatus",
        "anime/?status=&type=Movie&order=update" to "Movie"
    )

    // MAIN PAGE
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (request.data) {
            ""              -> getLatestFromHome(page, request.name)
            "popular-today" -> getPopularTodayFromHome(page, request.name)
            "rekomendasi"   -> getRekomendasiFromHome(page, request.name)
            else            -> getKategoriPage(page, request)
        }
    }

    // Rilisan Terbaru
    private suspend fun getLatestFromHome(page: Int, name: String): HomePageResponse {
        val url      = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url, headers = browserHeaders).document

        val section = document.select("div.bixbox").firstOrNull {
            it.selectFirst("div.releases.latesthome") != null
        }
        val items = section?.select("article.bs")
            ?.mapNotNull { it.toSearchResult() }
            ?: emptyList()

        val hasNext = hasNextPage(document)
        return newHomePageResponse(HomePageList(name, items, isHorizontalImages = false), hasNext)
    }

    // Populer Hari Ini
    private suspend fun getPopularTodayFromHome(page: Int, name: String): HomePageResponse {
        if (page > 1) return emptyHomeResponse(name)
        val document = app.get(mainUrl, headers = browserHeaders).document

        val section = document.select("div.bixbox").firstOrNull {
            it.selectFirst("div.releases.hothome") != null
        }
        val items = section
            ?.select("div.listupd.popularslider article.bs")
            ?.mapNotNull { it.toSearchResult() }
            ?: emptyList()

        return newHomePageResponse(HomePageList(name, items, isHorizontalImages = false), false)
    }

    // Rekomendasi
    private suspend fun getRekomendasiFromHome(page: Int, name: String): HomePageResponse {
        if (page > 1) return emptyHomeResponse(name)
        val document = app.get(mainUrl, headers = browserHeaders).document

        val section = document.select("div.bixbox").firstOrNull {
            it.selectFirst("div.releases h3")
                ?.text()?.contains("Rekomendasi", ignoreCase = true) == true
        }
        val items = section?.select("div.series-gen article.bs")
            ?.mapNotNull { it.toSearchResult() }
            ?: emptyList()

        return newHomePageResponse(HomePageList(name, items, isHorizontalImages = false), false)
    }

    // kategori
    private suspend fun getKategoriPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data     = request.data
        val hasQuery = '?' in data

        val url = when {
            hasQuery && page == 1  -> "$mainUrl/$data"
            hasQuery               -> "$mainUrl/$data&page=$page"
            page == 1              -> "$mainUrl/$data/"
            else                   -> "$mainUrl/$data/page/$page/"
        }

        val document = app.get(url, headers = browserHeaders).document
        val items    = document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
        val hasNext = hasNextPage(document)

        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), hasNext)
    }

    // SEARCH
    override suspend fun search(query: String): List<SearchResponse> {
        val url      = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url, headers = browserHeaders).document
        return document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
    }


    // CARD PARSER
    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("div.bsx > a") ?: return null
        val href = fixUrl(aTag.attr("href"))

        val title = selectFirst("div.tt")?.ownText()?.trim()
            .takeIf { !it.isNullOrBlank() }
            ?: aTag.attr("title").ifBlank { aTag.text() }

        val poster = extractPoster(aTag)

        val typeText = selectFirst(".typez")?.text()?.trim() ?: ""
        val tvType   = if (typeText.contains("movie", ignoreCase = true)) TvType.Movie else TvType.Anime

        val epxText   = selectFirst("span.epx")?.text()?.trim() ?: ""
        var epxNumber = Regex("\\d+").find(epxText)?.value?.toIntOrNull()

        if (epxNumber == null) {
            val titleText = aTag.attr("title")
            epxNumber = Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE)
                .find(titleText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        if (epxNumber == null) {
            val h2Text = selectFirst("h2")?.text() ?: ""
            epxNumber = Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE)
                .find(h2Text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        val dubStatus = DubStatus.Subbed

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            addDubStatus(dubStatus, epxNumber)
        }
    }

    private fun extractPoster(aTag: Element): String? {
        val img = aTag.selectFirst("img") ?: return null
        val raw = img.attr("src")
            .ifBlank { img.attr("data-src") }
            .ifBlank { img.attr("data-lazy-src") }
        val fixed = if (raw.startsWith("//")) "https:$raw" else raw
        return fixUrlNull(fixed)
    }

    private fun parseEpisodeFromSpan(spanText: String, h3Text: String): Pair<Int?, String?> {
        val parts   = spanText.split(" - ")
        val epsPart = parts.getOrNull(0)?.trim() ?: ""

        var epNum = Regex("\\d+").find(epsPart)?.value?.toIntOrNull()

        if (epNum == null && h3Text.isNotBlank()) {
            epNum = Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE)
                .find(h3Text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        val datePattern = Regex(
            "^(January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d"
        )
        val secondPart = parts.getOrNull(1)?.trim()
        val epTheme = if (secondPart != null && !datePattern.containsMatchIn(secondPart)) {
            secondPart.ifBlank { null }
        } else null

        return Pair(epNum, epTheme)
    }

    // LOAD
    override suspend fun load(url: String): LoadResponse {
        val isEpisodeUrl = url.contains("-episode-") || url.contains("-subtitle-indonesia")

        val seriesUrl = if (isEpisodeUrl) {
            url.replace(Regex("-episode-[^/]+/?$"), "/")
               .replace(Regex("-subtitle-indonesia/?$"), "/")
        } else url

        if (isEpisodeUrl) {
            val epDoc = app.get(fixUrl(url), headers = browserHeaders).document

            val title = epDoc.selectFirst("h1.entry-title")?.text()
                ?.replace(Regex("\\s*Episode\\s+\\d+.*", RegexOption.IGNORE_CASE), "")?.trim()
                ?: epDoc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.replace(Regex("\\s*Episode\\s+\\d+.*", RegexOption.IGNORE_CASE), "")?.trim()
                ?: ""

            val poster      = epDoc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: epDoc.selectFirst("div.ime > img")?.attr("src") ?: ""
            val description = epDoc.selectFirst("div.entry-content")?.text()?.trim()
            val genres      = epDoc.select("div.genxed a").map { it.text().trim() }
            val showStatus  = parseShowStatus(epDoc.select("div.spe span").map { it.text() })

            val seenIds = mutableSetOf<Int>()
            val episodes = epDoc.select("div.episodelist ul li").mapNotNull { li ->
                val a      = li.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrl(a.attr("href"))

                val dataId = li.attr("data-id").toIntOrNull() ?: return@mapNotNull null
                if (!seenIds.add(dataId)) return@mapNotNull null

                val spanText = li.selectFirst("div.playinfo span")?.text()?.trim() ?: ""
                val h3Text   = li.selectFirst("div.playinfo h3")?.text()?.trim() ?: ""
                val (epNum, epTheme) = parseEpisodeFromSpan(spanText, h3Text)

                val epPoster = li.selectFirst("div.thumbnel img")?.run {
                    attr("src").ifBlank { attr("data-src") }
                }.orEmpty()

                newEpisode(epHref) {
                    this.name      = epTheme
                    this.episode   = epNum
                    this.posterUrl = epPoster.ifBlank { poster }
                }
            }.reversed()

            val cleanSeriesUrl = epDoc.selectFirst("div.headlist a")?.attr("href")
                ?.let { fixUrl(it) } ?: seriesUrl

            return newTvSeriesLoadResponse(title, cleanSeriesUrl, TvType.Anime, episodes) {
                this.posterUrl  = poster
                this.plot       = description
                this.tags       = genres
                this.showStatus = showStatus
            }
        }

        val document    = app.get(seriesUrl, headers = browserHeaders).document
        val title       = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster      = document.selectFirst("div.ime > img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val description = document.selectFirst("div.entry-content, div.desc")?.text()?.trim()
        val genres      = document.select("div.genxed a").map { it.text().trim() }
        val showStatus  = parseShowStatus(document.select("div.spe span").map { it.text() })

        val episodeList = document.select("div.eplister ul li")
        val isSeries    = episodeList.isNotEmpty()
        val tvType      = if (isSeries) TvType.Anime else TvType.Movie

        val episodes = if (isSeries) {
            val seenHrefs = mutableSetOf<String>()
            episodeList.mapNotNull { li ->
                val a      = li.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrl(a.attr("href"))
                if (!seenHrefs.add(epHref)) return@mapNotNull null

                val epNumRaw = li.selectFirst("div.epl-num")?.text()?.trim() ?: ""
                val epNum    = Regex("\\d+").findAll(epNumRaw).lastOrNull()?.value?.toIntOrNull()
                val epTitle  = li.selectFirst("div.epl-title")?.text()?.trim()?.ifBlank { null }
                val epPoster = li.selectFirst("div.epl-image img")?.run {
                    attr("src").ifBlank { attr("data-src") }
                }.orEmpty()

                newEpisode(epHref) {
                    this.name      = epTitle
                    this.episode   = epNum
                    this.posterUrl = epPoster.ifBlank { poster }
                }
            }.reversed()
        } else {
            // Movie: decode Base64 iframe
            val base64 = document.selectFirst(".mobius option[value]")?.attr("value")?.trim()
            var playUrl: String? = null
            if (!base64.isNullOrBlank()) {
                try {
                    val decoded = base64Decode(base64)
                    val rawSrc  = Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
                    if (!rawSrc.isNullOrBlank()) {
                        playUrl = if (rawSrc.startsWith("http")) rawSrc else "https:$rawSrc"
                    }
                } catch (_: Exception) {}
            }
            listOf(newEpisode(playUrl ?: seriesUrl) {
                name      = "Movie"
                posterUrl = poster
            })
        }

        return newTvSeriesLoadResponse(title, seriesUrl, tvType, episodes) {
            this.posterUrl  = poster
            this.plot       = description
            this.tags       = genres
            this.showStatus = showStatus
        }
    }

    // LOAD LINKS
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
                val decoded   = base64Decode(base64)
                val iframeSrc = Jsoup.parse(decoded).selectFirst("iframe")
                    ?.attr("src")?.ifBlank { null }?.trim() ?: return@forEach
                val finalUrl = if (iframeSrc.startsWith("http")) iframeSrc else "https:$iframeSrc"
                loadExtractor(finalUrl, data, subtitleCallback, callback)
            } catch (_: Exception) {}
        }
        return true
    }

    // HELPERS

    /**
     * Parse ShowStatus dari span-span div.spe.
     * HTML: <span><b>Status:</b> Ongoing</span>
     */
    private fun parseShowStatus(spans: List<String>): ShowStatus? {
        val text = spans.firstOrNull { it.contains("Status", ignoreCase = true) } ?: return null
        return when {
            text.contains("Ongoing",   ignoreCase = true) -> ShowStatus.Ongoing
            text.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
            text.contains("Hiatus",    ignoreCase = true) -> ShowStatus.Completed
            else -> null
        }
    }

    private fun emptyHomeResponse(name: String) = newHomePageResponse(
        HomePageList(name, emptyList(), isHorizontalImages = false), false
    )

    private fun hasNextPage(doc: Document): Boolean {
        if (doc.selectFirst("div.hpage a.r") != null) return true
        if (doc.selectFirst("a.next.page-numbers") != null) return true
        if (doc.selectFirst("link[rel=next]") != null) return true
        return false
    }
}
