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
        "User-Agent"              to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
        "Accept"                  to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language"         to "id-ID,id;q=0.9",
        "Cache-Control"           to "no-cache",
        "Pragma"                  to "no-cache",
        "Sec-Fetch-Dest"          to "document",
        "Sec-Fetch-Mode"          to "navigate",
        "Sec-Fetch-Site"          to "none",
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

    private suspend fun getLatestFromHome(page: Int, name: String): HomePageResponse {
        val url      = if (page == 1) mainUrl else "$mainUrl/page/$page/"
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
            list    = HomePageList(name = name, list = items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private suspend fun getPopularTodayFromHome(page: Int, name: String): HomePageResponse {
        if (page > 1) return emptyHomeResponse(name)

        val document = app.get(mainUrl, headers = browserHeaders).document
        val section = document.select("div.bixbox").firstOrNull { box ->
            box.selectFirst("div.releases.hothome") != null
        }
        val items = section
            ?.select("div.listupd.popularslider article.bs")
            ?.mapNotNull { it.toEpisodeSearchResult() }
            ?: emptyList()

        return newHomePageResponse(
            list    = HomePageList(name = name, list = items, isHorizontalImages = false),
            hasNext = false
        )
    }

    private suspend fun getRekomendasiFromHome(page: Int, name: String): HomePageResponse {
        if (page > 1) return emptyHomeResponse(name)

        val document = app.get(mainUrl, headers = browserHeaders).document
        val section = document.select("div.bixbox").firstOrNull { box ->
            box.selectFirst("div.releases h3")
                ?.text()?.contains("Rekomendasi", ignoreCase = true) == true
        }
        // Ambil dari semua tab-pane (termasuk hidden) agar semua genre masuk
        val items = section
            ?.select("div.series-gen article.bs")
            ?.mapNotNull { it.toSearchResult() }
            ?: emptyList()

        return newHomePageResponse(
            list    = HomePageList(name = name, list = items, isHorizontalImages = false),
            hasNext = false
        )
    }

    private suspend fun getKategoriPage(page: Int, request: MainPageRequest): HomePageResponse {
        val hasQuery = '?' in request.data
        val url = when {
            hasQuery && page == 1  -> "$mainUrl/${request.data}"
            hasQuery && page > 1   -> "$mainUrl/${request.data}&page=$page"
            !hasQuery && page == 1 -> "$mainUrl/${request.data}/"
            else                   -> "$mainUrl/${request.data}/page/$page/"
        }

        val document = app.get(url, headers = browserHeaders).document
        val items    = document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
        // Selector next page: a.r di div.hpage (sama dengan home)
        val hasNext  = document.selectFirst("div.hpage a.r") != null

        return newHomePageResponse(
            list    = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    // SEARCH

    override suspend fun search(query: String): List<SearchResponse> {
        val url      = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url, headers = browserHeaders).document
        return document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
    }

    // CARD PARSERS

    /**
     * Series card — href mengarah ke URL series (bukan episode).
     * Dipakai di kategori, search, dan rekomendasi.
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val aTag  = selectFirst("div.bsx > a") ?: return null
        val href  = fixUrl(aTag.attr("href"))
        val poster = extractPoster(aTag)

        val seriesTitle = selectFirst("div.tt")?.ownText()?.trim()
            .takeIf { !it.isNullOrBlank() }
            ?: aTag.attr("title").ifBlank { aTag.text() }

        val typeText = selectFirst(".typez")?.text() ?: ""
        val type = if (typeText.contains("movie", ignoreCase = true)) TvType.Movie else TvType.Anime

        return newMovieSearchResponse(seriesTitle, href, type) {
            this.posterUrl = poster
        }
    }

    /**
     * Episode card — href mengarah ke URL episode.
     * load() akan redirect ke series secara otomatis.
     * Dipakai di Rilisan Terbaru & Populer Hari Ini.
     */
    private fun Element.toEpisodeSearchResult(): SearchResponse? {
        val aTag       = selectFirst("div.bsx > a") ?: return null
        val episodeHref = fixUrl(aTag.attr("href"))
        val poster     = extractPoster(aTag)

        val seriesTitle = selectFirst("div.tt")?.ownText()?.trim()
            .takeIf { !it.isNullOrBlank() }
            ?: aTag.attr("title").ifBlank { aTag.text() }

        val typeText = selectFirst(".typez")?.text() ?: ""
        val type = if (typeText.contains("movie", ignoreCase = true)) TvType.Movie else TvType.Anime

        return newMovieSearchResponse(seriesTitle, episodeHref, type) {
            this.posterUrl = poster
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

    // EPISODE SPAN PARSER

    /**
     * Parse teks span di daftar episode (episode page):
     *   "Eps 605 - Empat Teknik Pedang - May 15, 2026"  → (605, "Empat Teknik Pedang")
     *   "Eps 605 - May 15, 2026"                        → (605, null)
     *   "Eps Niat Pedang Void - September 17, 2021"     → (null, null)  ← fallback ke h3
     *   "Eps - July 18, 2022"                           → (null, null)  ← fallback ke h3
     *
     * @param h3Text teks h3 di playinfo sebagai fallback untuk nomor episode.
     *               Format: "Series Name Episode 188 [Season 2] Subtitle Indonesia"
     */
    private fun parseEpisodeFromSpan(spanText: String, h3Text: String): Pair<Int?, String?> {
        val parts   = spanText.split(" - ")
        val epsPart = parts.getOrNull(0)?.trim() ?: ""

        // Ambil angka dari "Eps 605", "Eps 101", dll.
        var epNum = Regex("\\d+").find(epsPart)?.value?.toIntOrNull()

        // Fallback: kalau span tidak punya angka, ambil dari h3
        // Format h3: "… Episode 188 [Season 2] Subtitle Indonesia"
        if (epNum == null && h3Text.isNotBlank()) {
            epNum = Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE)
                .find(h3Text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        // Theme title: part ke-2 hanya jika bukan tanggal
        val dateRegex = Regex(
            "^(January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d"
        )
        val secondPart = parts.getOrNull(1)?.trim()
        val epTheme = if (secondPart != null && !dateRegex.containsMatchIn(secondPart)) {
            secondPart.ifBlank { null }
        } else null

        return Pair(epNum, epTheme)
    }

    // LOAD

    override suspend fun load(url: String): LoadResponse {
        val isEpisodeUrl = url.contains("-episode-") || url.contains("-subtitle-indonesia")

        // Derive series URL dari episode URL
        val seriesUrl = if (isEpisodeUrl) {
            url.replace(Regex("-episode-[^/]+/?$"), "/")
               .replace(Regex("-subtitle-indonesia/?$"), "/")
        } else url

        // Episode page path
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

            // Episode list dari panel episodelist (bukan eplister)
            // Deduplicate dengan seenIds agar tidak ada episode ganda
            val seenIds = mutableSetOf<Int>()
            val episodes = epDoc.select("div.episodelist ul li").mapNotNull { li ->
                val a      = li.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrl(a.attr("href"))

                // data-id unik per episode — gunakan untuk dedup
                val dataId = li.attr("data-id").toIntOrNull() ?: return@mapNotNull null
                if (!seenIds.add(dataId)) return@mapNotNull null  // skip duplikat

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
            }.reversed() // dari ep terkecil → terbesar

            val cleanSeriesUrl = epDoc.selectFirst("div.headlist a")?.attr("href")
                ?.let { fixUrl(it) } ?: seriesUrl

            return newTvSeriesLoadResponse(title, cleanSeriesUrl, TvType.Anime, episodes) {
                this.posterUrl  = poster
                this.plot       = description
                this.tags       = genres
                this.showStatus = showStatus
            }
        }

        // Series / Movie page path
        val document = app.get(seriesUrl, headers = browserHeaders).document

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
            // Deduplicate dengan seenNums — episode di series page memakai epl-num
            val seenNums = mutableSetOf<String>()
            episodeList.mapNotNull { li ->
                val a = li.selectFirst("a") ?: return@mapNotNull null
                val epHref   = fixUrl(a.attr("href"))

                // Gunakan href sebagai kunci dedup (paling unik)
                if (!seenNums.add(epHref)) return@mapNotNull null

                val epNumRaw = li.selectFirst("div.epl-num")?.text()?.trim() ?: ""
                // Ambil ANGKA TERAKHIR untuk hindari Season prefix (e.g. "Season 2 Episode 605" → 605)
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
            // Movie: decode Base64 iframe dari option pertama
            val firstOption = document.selectFirst(".mobius option[value]")
            val base64      = firstOption?.attr("value")?.trim()
            var playUrl: String? = null
            if (!base64.isNullOrBlank()) {
                try {
                    val decoded = base64Decode(base64)
                    val iframe  = Jsoup.parse(decoded).selectFirst("iframe")
                    val rawSrc  = iframe?.attr("src")
                    if (!rawSrc.isNullOrBlank()) {
                        playUrl = if (rawSrc.startsWith("http")) rawSrc else "https:$rawSrc"
                    }
                } catch (_: Exception) {}
            }
            if (playUrl == null) playUrl = seriesUrl
            listOf(newEpisode(playUrl) { name = "Movie"; posterUrl = poster })
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
                val decoded  = base64Decode(base64)
                val iframe   = Jsoup.parse(decoded).selectFirst("iframe")
                val iframeSrc = iframe?.attr("src")
                    ?.ifBlank { iframe.attr("data-src") }?.trim()
                if (!iframeSrc.isNullOrBlank()) {
                    val finalUrl = if (iframeSrc.startsWith("http")) iframeSrc else "https:$iframeSrc"
                    loadExtractor(finalUrl, data, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }
        return true
    }

    // HELPERS

    /**
     * Parse ShowStatus dari list teks span di div.spe.
     * Format HTML: <span><b>Status:</b> Ongoing</span>
     */
    private fun parseShowStatus(spans: List<String>): ShowStatus? {
        val statusText = spans.firstOrNull { it.contains("Status", ignoreCase = true) }
            ?: return null
        return when {
            statusText.contains("Ongoing",   ignoreCase = true) -> ShowStatus.Ongoing
            statusText.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
            statusText.contains("Hiatus",    ignoreCase = true) -> ShowStatus.Completed
            else -> null
        }
    }

    private fun emptyHomeResponse(name: String) = newHomePageResponse(
        list    = HomePageList(name = name, list = emptyList(), isHorizontalImages = false),
        hasNext = false
    )
}
