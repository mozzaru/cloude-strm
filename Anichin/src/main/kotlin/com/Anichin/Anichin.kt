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
     * Anichin's real synopsis lives in `div.desc.mindes`, but its internal markup is
     * NOT consistent across pages — confirmed by inspecting several live pages (2026-07):
     *  - `<h4>Title [Native]</h4>` + a bare text node holding the synopsis
     *  - just a `<p>` with the synopsis (no title heading at all)
     *  - a single `<h4>[Title]synopsis</h4>` with title and synopsis run together
     *  - two `<h4>`s: one holding only `[Title]`, the next holding the real synopsis
     *  - two `<h5>`s: one holding `"Title - synopsis"` (dash-joined), the next a note
     * All variants end with an empty `<span class="colap">` (a "read more" toggle).
     * Some titles (freshly-added ones) have no `div.desc.mindes` or `div.desc` at all —
     * the site genuinely hasn't published a synopsis yet, not a scraping bug.
     *
     * Rather than match a specific tag shape, this walks every direct child, drops any
     * child whose entire text is just the title (handles the "title-only heading"
     * variant), then strips a leading "{title}", "[{title}]", "{title} [{native}]", or
     * "{title} - " prefix from whatever's left (handles the "title glued to synopsis"
     * variants). A `<p>`-only block with no title text passes through untouched.
     */
    /**
     * IMPORTANT — this has now been checked against TWO live pages with genuinely different
     * internal structure (2026-07-23):
     *   - /slay-the-gods-season-2/: "Sinopsis {Judul}" heading sits ALONE inside its own
     *     title-wrapper div (no sibling at all), and the real synopsis lives in a SEPARATE
     *     `div.entry-content` block further down (title-only heading + paragraph inside it).
     *   - /renegade-immortal/: "Sinopsis {Judul}" heading is directly followed by ONE paragraph
     *     in "Judul – sinopsis" (dash-joined) form — no separate title-only heading at all.
     * There is also a class named `mindesc` (NOT `desc mindes` as previously assumed — that
     * combined class does not actually exist) that holds a totally different, site-wide
     * alt-title spam line ("nonton {title} terlengkap, {title} Subtitle Indonesia, {title} sub
     * indo, download {title} sub indo, streaming {title} di Anichin.") — never the real plot.
     * And a plain `div.desc` holds the OTHER, longer SEO filler paragraph ("Tonton streaming...
     * kamu juga bisa download gratis... MP4 MKV hardsub softsub...").
     *
     * Because structure genuinely varies per title, extraction now tries, in order:
     *   1. `div.entry-content` (proven correct on both tested pages) — primary.
     *   2. Walking forward from the "Sinopsis ..." heading — fallback for the (probably rarer)
     *      case where no entry-content div exists.
     *   3. `div.desc.mindes` — kept only in case an older page variant truly uses it.
     *   4. Any remaining entry-content/desc block that isn't a known filler.
     * Every step is guarded by a boilerplate/spam pattern so filler text is never returned
     * even if it happens to be the first match for a given selector on some page.
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

        // 1) PRIMARY: div.entry-content, verified correct on two structurally different pages.
        doc.select("div.entry-content").firstOrNull { !boilerplateSynopsisPattern.containsMatchIn(it.text()) }
            ?.let { container ->
                cleanDescContainer(container)?.let { return it }
                // fall through to plain text if the child-node walk finds nothing usable
                val plain = container.text().trim()
                if (plain.isNotBlank() && !boilerplateSynopsisPattern.containsMatchIn(plain)) return plain
            }

        // 2) Fallback: walk forward from the "Sinopsis ..." heading (handles pages without an
        //    entry-content div at all).
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
                    if (!isTitleOnly) break // hit the next real section, e.g. cast list
                } else if (nodeText.isNotBlank()) {
                    collected.add(nodeText)
                }
                node = node.nextElementSibling()
            }
            val joined = collected.joinToString("\n\n").trim()
            if (joined.isNotBlank() && !boilerplateSynopsisPattern.containsMatchIn(joined)) {
                leadingTitlePattern?.let { return it.replaceFirst(joined, "").trim() }
                return joined
            }
        }

        // 3) Kept in case some page variant genuinely has both classes together.
        val descContainer = doc.select("div.desc.mindes").firstOrNull { block ->
            !boilerplateSynopsisPattern.containsMatchIn(block.text())
        }
        if (descContainer != null) {
            cleanDescContainer(descContainer)?.let { return it }
        }

        // 4) Last resort.
        val fallback = doc.select("div.entry-content, div.desc").firstOrNull {
            !boilerplateSynopsisPattern.containsMatchIn(it.text())
        }
        return fallback?.text()?.trim()
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
            val base64 = document.selectFirst(".mobius option[value]")?.attr("value")?.trim()
            var playUrl: String? = null
            if (!base64.isNullOrBlank()) {
                try {
                    val decoded = base64Decode(base64)
                    val rawSrc = Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
                    if (!rawSrc.isNullOrBlank()) {
                        playUrl = if (rawSrc.startsWith("http")) rawSrc else "https:$rawSrc"
                    }
                } catch (_: Exception) {}
            }
            listOf(newEpisode(playUrl ?: seriesUrl) {
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
        val serverCount = document.select(".mobius option").size
        Log.d("AnichinLoadLinks", "Found $serverCount server options")
        document.select(".mobius option").amap { server ->
            val label = server.text().trim()
            val base64 = server.attr("value")
            if (base64.isBlank()) {
                Log.w("AnichinLoadLinks", "Server '$label': blank base64, skipping")
                return@amap
            }
            val decoded = try { base64Decode(base64) } catch (e: Exception) {
                Log.w("AnichinLoadLinks", "Server '$label': base64 decode failed: ${e.message}")
                return@amap
            }
            val doc = Jsoup.parse(decoded)
            val iframes = doc.select("iframe")
            Log.d("AnichinLoadLinks", "Server '$label': decoded HTML has ${iframes.size} iframes")
            if (iframes.isEmpty()) {
                Log.w("AnichinLoadLinks", "Server '$label': no iframe found")
                Log.d("AnichinLoadLinks", "Server '$label': decoded HTML: ${decoded.take(500)}")
                return@amap
            }
            val href = iframes.attr("src")
            if (href.isBlank()) {
                Log.w("AnichinLoadLinks", "Server '$label': iframe src is blank")
                iframes.forEachIndexed { i, f -> Log.d("AnichinLoadLinks", "  iframe[$i] attrs: ${f.attributes()}") }
                return@amap
            }
            val url = httpsify(href)
            Log.d("AnichinLoadLinks", "Server '$label': loading extractor URL: $url")
            if (url.contains("rpmvid.com") || url.contains("rpmvid")) {
                Log.d("AnichinLoadLinks", "Server '$label': using direct RpmShare handler")
                try {
                    RpmShare().getUrl(url, null, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.w("AnichinLoadLinks", "RpmShare direct call FAILED: ${e.message}")
                }
            } else {
                loadExtractor(url, subtitleCallback, callback)
            }
        }
        return true
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
