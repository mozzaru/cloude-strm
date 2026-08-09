package com.Anichin

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode

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
        "" to "Rilisan Terbaru",
        "popular-today" to "Populer Hari Ini",
        "rekomendasi" to "Rekomendasi",
        "ongoing" to "Series Ongoing",
        "completed" to "Series Completed",
        "drop" to "Series Drop/Hiatus",
        "anime/?status=&type=Movie&order=update" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (request.data) {
            "" -> getLatestFromHome(page, request.name)
            "popular-today" -> getPopularTodayFromHome(page, request.name)
            "rekomendasi" -> getRekomendasiFromHome(page, request.name)
            else -> getKategoriPage(page, request)
        }
    }

    private suspend fun getLatestFromHome(page: Int, name: String): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
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

    private suspend fun getKategoriPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val hasQuery = '?' in data
        val url = when {
            hasQuery && page == 1 -> "$mainUrl/$data"
            hasQuery -> "$mainUrl/$data&page=$page"
            page == 1 -> "$mainUrl/$data/"
            else -> "$mainUrl/$data/page/$page/"
        }
        val document = app.get(url, headers = browserHeaders).document
        val items = document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
        val hasNext = hasNextPage(document)
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url, headers = browserHeaders).document
        return document.select("div.listupd article.bs").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("div.bsx > a") ?: return null
        val href = fixUrl(aTag.attr("href"))
        val title = selectFirst("div.tt")?.ownText()?.trim()
            .takeIf { !it.isNullOrBlank() }
            ?: aTag.attr("title").ifBlank { aTag.text() }
        val poster = extractPoster(aTag)
        val typeText = selectFirst(".typez")?.text()?.trim() ?: ""
        val tvType = if (typeText.contains("movie", ignoreCase = true)) TvType.Movie else TvType.Anime
        val epxText = selectFirst("span.epx")?.text()?.trim() ?: ""
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
        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            addDubStatus(DubStatus.Subbed, epxNumber)
        }
    }

    private fun extractPoster(aTag: Element): String? {
        val img = aTag.selectFirst("img") ?: return null
        val raw = img.attr("src")
            .ifBlank { img.attr("data-src") }
            .ifBlank { img.attr("data-lazy-src") }
        val cleaned = raw.split("?")[0]
        val fixed = if (cleaned.startsWith("//")) "https:$cleaned" else cleaned
        return fixUrlNull(fixed)
    }

    /**
     * Sinopsis extraction strategy (verified against live pages 2026-07):
     *   1. Series page: `div.bixbox.synp div.entry-content`
     *   2. Episode page: `div.single-info div.desc.mindes`
     *   3. Walk forward from a "Sinopsis ..." heading
     *   4. Generic non-spam `div.entry-content` or `div.desc`
     *
     * Each step is guarded by a boilerplate filter so SEO filler
     * ("Tonton streaming...", "nonton ... terlengkap") is never returned.
     */
    private val boilerplateSynopsisPattern = Regex(
        "Tonton streaming.{0,80}di Anichin|kamu juga bisa download gratis|hardsub softsub subtitle" +
            "|nonton .{0,80} terlengkap|.{0,80} sub indo, download .{0,80} sub indo",
        RegexOption.IGNORE_CASE
    )

    private fun extractSinopsis(doc: Document, title: String): String? {
        val cleanTitle = title.trim()
        val titleOnlyPattern = if (cleanTitle.isNotBlank())
            Regex("""^\[?\Q$cleanTitle\E\]?$""", RegexOption.IGNORE_CASE) else null
        val leadingTitlePattern = if (cleanTitle.isNotBlank())
            Regex("""^\[?\Q$cleanTitle\E\]?(?:\s*\[[^\]]*])?\s*[-–—]?\s*""", RegexOption.IGNORE_CASE)
            else null

        fun cleanDescContainer(container: Element): String? {
            val segments = mutableListOf<String>()
            for (node in container.childNodes()) {
                val text = when {
                    node is TextNode -> node.text().trim()
                    node is Element && node.tagName().equals("span", true) && node.hasClass("colap") -> ""
                    node is Element -> node.text().trim()
                    else -> ""
                }
                if (text.isBlank()) continue
                if (titleOnlyPattern?.matches(text) == true) continue
                segments.add(text)
            }
            if (segments.isEmpty()) return null
            leadingTitlePattern?.let { segments[0] = it.replaceFirst(segments[0], "").trim() }
            return segments.filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { null }
        }

        fun isSpam(text: String?) = text != null && boilerplateSynopsisPattern.containsMatchIn(text)

        // 1) Series page: div.bixbox.synp div.entry-content (most specific, never spam).
        doc.select("div.bixbox.synp div.entry-content").firstOrNull { !isSpam(it.text()) }
            ?.let { container ->
                cleanDescContainer(container)?.let { return it }
                val plain = container.text().trim()
                if (plain.isNotBlank() && !isSpam(plain)) return plain
            }

        // 2) Episode sidebar: div.single-info div.desc.mindes (holds real synopsis on episode pages).
        doc.select("div.single-info div.desc.mindes").firstOrNull { !isSpam(it.text()) }
            ?.let { container ->
                cleanDescContainer(container)?.let { return it }
                val plain = container.text().trim()
                if (plain.isNotBlank() && !isSpam(plain)) return plain
            }

        // 3) Walk forward from the "Sinopsis ..." heading (handles pages without standard containers).
        val sinopsisHeading = doc.select("h1, h2, h3, h4, h5").firstOrNull {
            it.text().trim().startsWith("Sinopsis", ignoreCase = true)
        }
        if (sinopsisHeading != null) {
            val collected = mutableListOf<String>()
            var node = sinopsisHeading.nextElementSibling()
            var steps = 0
            while (node != null && steps < 8) {
                steps++
                val nodeText = node.text().trim()
                val isHeading = Regex("^[Hh][1-6]$").matches(node.tagName())
                if (isHeading) {
                    val isTitleOnly = titleOnlyPattern?.matches(nodeText) == true
                    if (!isTitleOnly) break
                } else if (nodeText.isNotBlank()) {
                    collected.add(nodeText)
                }
                node = node.nextElementSibling()
            }
            val joined = collected.joinToString("\n\n").trim()
            if (joined.isNotBlank() && !isSpam(joined)) {
                leadingTitlePattern?.let { return it.replaceFirst(joined, "").trim() }
                return joined
            }
        }

        // 4) Generic fallback — only non-spam div.entry-content or div.desc.
        val fallback = doc.select("div.entry-content, div.desc").firstOrNull { !isSpam(it.text()) }
        if (fallback != null) {
            cleanDescContainer(fallback)?.let { return it }
            val plain = fallback.text().trim()
            if (plain.isNotBlank() && !isSpam(plain)) return plain
        }

        return null
    }

    /**
     * Extracts the numeric site rating (e.g. "Rating 9.80" shown near the poster/Bookmark
     * button) — verified present in that exact "Rating <number>" text form live 2026-07-23.
     * Uses a text-regex rather than a guessed CSS class, since the raw class name wasn't
     * visible in the fetched output used to verify this. Run the accompanying Termux
     * diagnostic script if you want to swap this for a precise selector instead.
     */
    private fun extractRatingText(doc: Document): String? {
        val match = Regex("""Rating\s*[:\-]?\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
            .find(doc.text())
        return match?.groupValues?.getOrNull(1)
    }

    /**
     * Anichin always renders episode air dates in English month names, e.g. "July 18, 2026"
     * — verified against both `div.epl-date` (series page) and the tail of `div.playinfo span`
     * (episode page, "Eps 03 - July 18, 2026") via live HTML inspection (2026-07).
     */
    private fun parseEnglishDate(dateText: String?): Long? {
        if (dateText.isNullOrBlank()) return null
        return try {
            java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.ENGLISH)
                .parse(dateText.trim())?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun parseEpisodeFromSpan(spanText: String, h3Text: String): Triple<Int?, String?, Long?> {
        val parts = spanText.split(" - ")
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
        val isDate = secondPart != null && datePattern.containsMatchIn(secondPart)
        val epTheme = if (secondPart != null && !isDate) secondPart.ifBlank { null } else null
        val epDate = if (isDate) parseEnglishDate(secondPart) else null
        return Triple(epNum, epTheme, epDate)
    }

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

            val poster = epDoc.selectFirst("div.thumb img")?.attr("src")
                ?: epDoc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""

            val description = extractSinopsis(epDoc, title)
            val genres = epDoc.select("div.genxed a").map { it.text().trim() }
            val showStatus = parseShowStatus(epDoc.select("div.spe span").map { it.text() })
            val ratingText = extractRatingText(epDoc)

            val seenIds = mutableSetOf<Int>()
            val episodes = epDoc.select("div.episodelist ul li").mapNotNull { li ->
                val a = li.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrl(a.attr("href"))
                val dataId = li.attr("data-id").toIntOrNull() ?: return@mapNotNull null
                if (!seenIds.add(dataId)) return@mapNotNull null
                val spanText = li.selectFirst("div.playinfo span")?.text()?.trim() ?: ""
                val h3Text = li.selectFirst("div.playinfo h3")?.text()?.trim() ?: ""
                val (epNum, epTheme, epDate) = parseEpisodeFromSpan(spanText, h3Text)
                val epPoster = li.selectFirst("div.thumbnel img")?.run {
                    attr("src").ifBlank { attr("data-src") }
                }.orEmpty()
                newEpisode(epHref) {
                    this.name = epTheme
                    this.episode = epNum
                    this.posterUrl = epPoster.ifBlank { poster }
                    this.date = epDate
                }
            }.reversed()

            val cleanSeriesUrl = epDoc.selectFirst("div.headlist a")?.attr("href")
                ?.let { fixUrl(it) } ?: seriesUrl

            return newTvSeriesLoadResponse(title, cleanSeriesUrl, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                this.showStatus = showStatus
                this.score = Score.from10(ratingText?.toDoubleOrNull() ?: 0.0)
            }
        }

        val document = app.get(seriesUrl, headers = browserHeaders).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()

        val poster = document.selectFirst("div.thumb img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""

        val description = extractSinopsis(document, title)
        val genres = document.select("div.genxed a").map { it.text().trim() }
        val showStatus = parseShowStatus(document.select("div.spe span").map { it.text() })
        val ratingText = extractRatingText(document)

        val episodeList = document.select("div.eplister ul li")
        val isSeries = episodeList.isNotEmpty()
        val tvType = if (isSeries) TvType.Anime else TvType.Movie

        val episodes = if (isSeries) {
            val seenHrefs = mutableSetOf<String>()
            episodeList.mapNotNull { li ->
                val a = li.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrl(a.attr("href"))
                if (!seenHrefs.add(epHref)) return@mapNotNull null
                val epNumRaw = li.selectFirst("div.epl-num")?.text()?.trim() ?: ""
                val epNum = Regex("\\d+").findAll(epNumRaw).lastOrNull()?.value?.toIntOrNull()
                val epTitle = li.selectFirst("div.epl-title")?.text()?.trim()?.ifBlank { null }
                val epDate = parseEnglishDate(li.selectFirst("div.epl-date")?.text()?.trim())
                val epPoster = li.selectFirst("div.epl-image img")?.run {
                    attr("src").ifBlank { attr("data-src") }
                }.orEmpty()
                newEpisode(epHref) {
                    this.name = epTitle
                    this.episode = epNum
                    this.posterUrl = epPoster.ifBlank { poster }
                    this.date = epDate
                }
            }.reversed()
        } else {
            // For movies the series URL IS the player page (it holds the .mobius server
            // options). loadLinks() must be given that page so it can find the servers,
            // so we deliberately point the episode at seriesUrl and NOT at a resolved
            // external player URL (which would contain no .mobius options and yield
            // "no links found").
            listOf(newEpisode(seriesUrl) {
                name = "Movie"
                posterUrl = poster
            })
        }

        return newTvSeriesLoadResponse(title, seriesUrl, tvType, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.showStatus = showStatus
            this.score = Score.from10(ratingText?.toDoubleOrNull() ?: 0.0)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("AnichinLoadLinks", "Loading links for: $data")
        val document = app.get(data, headers = browserHeaders).document
        val servers = document.select(".mobius option")
        Log.d("AnichinLoadLinks", "Found ${servers.size} server options")

        servers.amap { server ->
            val label = server.text().trim()
            val base64 = server.attr("value")
            if (base64.isBlank()) {
                Log.d("AnichinLoadLinks", "Server '$label': blank value (placeholder), skipping")
                return@amap
            }
            val decoded = try {
                base64Decode(base64)
            } catch (e: Exception) {
                Log.w("AnichinLoadLinks", "Server '$label': base64 decode failed: ${e.message}")
                return@amap
            }
            val doc = Jsoup.parse(decoded)
            val iframes = doc.select("iframe")
            if (iframes.isEmpty()) {
                Log.w("AnichinLoadLinks", "Server '$label': no iframe in decoded HTML")
                return@amap
            }
            val href = iframes.attr("src")
            if (href.isBlank()) {
                Log.w("AnichinLoadLinks", "Server '$label': iframe src is blank")
                return@amap
            }
            val wrapperUrl = httpsify(href)
            Log.d("AnichinLoadLinks", "Server '$label': wrapper=$wrapperUrl")

            // The decoded iframe points at an anichin.moe/stream/<token> wrapper which:
            //   * returns HTTP 403 "hanya dapat diputar dari halaman anichin.moe" without a
            //     Referer, and
            //   * has NO extractor registered (only its inner iframe does).
            // We must resolve it with a Referer to get the real player (ok.ru, dailymotion,
            // rpmvid, rumble, ...) before dispatching to an extractor.
            val innerUrl = resolveStreamWrapper(wrapperUrl, data) ?: run {
                Log.w("AnichinLoadLinks", "Server '$label': could not resolve wrapper")
                return@amap
            }
            Log.d("AnichinLoadLinks", "Server '$label': inner=$innerUrl")

            try {
                dispatchPlayer(innerUrl, data, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.w("AnichinLoadLinks", "Server '$label': extractor failed: ${e.message}")
            }
        }
        return true
    }

    /**
     * Resolves an `https://anichin.moe/stream/<token>` wrapper into the real player URL it
     * embeds. These wrappers are protected by a Referer check (403 otherwise) and their
     * response is a tiny HTML document containing a single `<iframe>` pointing at the actual
     * host (ok.ru, geo.dailymotion.com, rpmvid, rumble, d.tube, ...).
     *
     * For the anichin-player.web.id relay (OK.ru / Dailymotion) we short-circuit to the final
     * ok.ru / geo.dailymotion URL directly so we rely on the well-tested core extractors and
     * avoid an extra, referer-dependent hop.
     */
    private suspend fun resolveStreamWrapper(wrapperUrl: String, referer: String): String? {
        if (!wrapperUrl.contains("$mainUrl/stream/") && !wrapperUrl.contains("anichin.moe/stream/")) {
            return wrapperUrl
        }
        return try {
            val innerUrl = fetchStreamIframe(wrapperUrl, referer) ?: return null

            when {
                // anichin-player.web.id/index.php?ok=<id>  ->  https://ok.ru/videoembed/<id>
                innerUrl.contains("anichin-player.web.id") && innerUrl.contains("?ok=") -> {
                    val okId = innerUrl.substringAfter("?ok=").substringBefore("&").trim()
                    "https://ok.ru/videoembed/$okId"
                }
                // anichin-player.web.id/index.php?url=<id> ->  geo.dailymotion.com/player.html?video=<id>
                innerUrl.contains("anichin-player.web.id") && innerUrl.contains("?url=") -> {
                    val dmId = innerUrl.substringAfter("?url=").substringBefore("&").trim()
                    "https://geo.dailymotion.com/player.html?video=$dmId"
                }
                // Defensive: a /stream/ wrapper could theoretically nest once more.
                innerUrl.contains("anichin.moe/stream/") ->
                    fetchStreamIframe(innerUrl, wrapperUrl) ?: innerUrl
                else -> innerUrl
            }
        } catch (e: Exception) {
            Log.w("AnichinLoadLinks", "resolveStreamWrapper failed for $wrapperUrl: ${e.message}")
            null
        }
    }

    /** Fetches an anichin.moe/stream/<token> wrapper (Referer-protected, 403 otherwise)
     *  and returns the absolute URL of its single inner iframe. */
    private suspend fun fetchStreamIframe(wrapperUrl: String, referer: String): String? {
        val wrapperHeaders = browserHeaders + mapOf(
            // We are loading the /stream/ endpoint from within an anichin.moe page, so it is
            // a same-origin, iframe-style sub-request.
            "Referer" to referer,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
        )
        val resp = app.get(wrapperUrl, headers = wrapperHeaders)
        val html = resp.text
        val innerSrc = Jsoup.parse(html).selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: return null
        return httpsify(innerSrc)
    }

    /**
     * Routes a resolved player URL to the right extractor. Most hosts are handled by the core
     * library or the bundled extractors via loadExtractor(); a few need the local bespoke
     * extractor called directly (or a default referer set).
     */
    private suspend fun dispatchPlayer(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        when {
            url.contains("rpmvid.com") -> {
                Log.d("AnichinLoadLinks", " -> RpmShare extractor")
                RpmShare().getUrl(url, referer, subtitleCallback, callback)
            }
            else -> {
                Log.d("AnichinLoadLinks", " -> loadExtractor: $url")
                loadExtractor(url, referer, subtitleCallback, callback)
            }
        }
    }

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
