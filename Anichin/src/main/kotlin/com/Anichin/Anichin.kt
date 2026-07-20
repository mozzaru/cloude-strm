package com.Anichin

import com.lagradost.api.Log
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
        val hasNext  = hasNextPage(document)
        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url      = "$mainUrl/?s=${query.replace(" ", "+")}"
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
     * Anichin renders two separate Indonesian text blocks on a series/episode page:
     *  1. A generic SEO blurb ("Download X Subtitle Indonesia, Nonton X Subtitle
     *     Indonesia, jangan lupa mengklik tombol like dan share ya...") sitting near
     *     the player, marked up as `div.entry-content` (no itemprop, plain class).
     *  2. The actual synopsis, living in `div.desc.mindes` — a sibling of
     *     `div.genxed` (the genre tags) inside `div.info-content` — prefixed by a
     *     heading that repeats the anime's title (e.g. "A Good Day to Ascend [择日飞升]")
     *     rather than the word "Sinopsis", and containing the real plot text in a
     *     child `<p>`.
     * Verified directly against the live page's HTML (2026-07); `div.desc.mindes p`
     * is the one selector that isolates block 2 without picking up block 1's text or
     * the leading title/native-title heading.
     */
    private fun extractSinopsis(doc: Document): String? {
        val container = doc.selectFirst("div.desc.mindes")
        val paragraphs = container?.select("p")?.map { it.text().trim() }?.filter { it.isNotBlank() }
        if (!paragraphs.isNullOrEmpty()) return paragraphs.joinToString("\n\n")

        val heading = doc.select("h1, h2, h3, h4, h5").firstOrNull {
            it.text().trim().startsWith("Sinopsis", ignoreCase = true)
        }
        val fromHeading = heading?.nextElementSibling()?.text()?.trim()
        if (!fromHeading.isNullOrBlank()) return fromHeading

        return doc.selectFirst("div.entry-content, div.desc")?.text()?.trim()
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
        val isDate = secondPart != null && datePattern.containsMatchIn(secondPart)
        val epTheme = if (secondPart != null && !isDate) secondPart.ifBlank { null } else null
        val epDate  = if (isDate) parseEnglishDate(secondPart) else null
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

            val description = extractSinopsis(epDoc)
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
                val (epNum, epTheme, epDate) = parseEpisodeFromSpan(spanText, h3Text)
                val epPoster = li.selectFirst("div.thumbnel img")?.run {
                    attr("src").ifBlank { attr("data-src") }
                }.orEmpty()
                newEpisode(epHref) {
                    this.name      = epTheme
                    this.episode   = epNum
                    this.posterUrl = epPoster.ifBlank { poster }
                    this.date      = epDate
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

        val document = app.get(seriesUrl, headers = browserHeaders).document
        val title    = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()

        val poster = document.selectFirst("div.thumb img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""

        val description = extractSinopsis(document)
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
                val epDate   = parseEnglishDate(li.selectFirst("div.epl-date")?.text()?.trim())
                val epPoster = li.selectFirst("div.epl-image img")?.run {
                    attr("src").ifBlank { attr("data-src") }
                }.orEmpty()
                newEpisode(epHref) {
                    this.name      = epTitle
                    this.episode   = epNum
                    this.posterUrl = epPoster.ifBlank { poster }
                    this.date      = epDate
                }
            }.reversed()
        } else {
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
