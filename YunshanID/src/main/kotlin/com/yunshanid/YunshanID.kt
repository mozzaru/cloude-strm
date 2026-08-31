package com.yunshanid

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

class YunshanID : MainAPI() {

    override var mainUrl = "https://yunshanid.site"
    override var name = "YunshanID"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Upgrade-Insecure-Requests" to "1",
    )

    private suspend fun get(url: String): String =
        app.get(url, headers = headers, referer = mainUrl).text

    override val mainPage = mainPageOf(
        "latest"    to "Rilisan Terbaru",
        "popular"   to "Populer",
        "On-Going"  to "Ongoing",
        "Completed" to "Completed",
        "Movie"     to "Movie",
        "all"       to "Semua Donghua"
    )

    // ============================================================
    // getMainPage / search
    // ============================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val entries = when (request.data) {
            "all"       -> scrapeJadwal()
            "On-Going"  -> scrapeFull().filter { it.status == "On-Going" }
            "Completed" -> scrapeFull().filter { it.status == "Completed" }
            else -> {
                val home = scrapeHome()
                when (request.data) {
                    "latest"  -> home
                    "popular" -> home.sortedByDescending { it.viewCount }
                    "Movie"   -> home.filter { it.type == "Movie" }
                    else      -> home
                }
            }
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = entries.map { it.toSearchResponse() },
                isHorizontalImages = false
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return scrapeJadwal()
            .filter { it.title.contains(query, ignoreCase = true) }
            .distinctBy { it.id }
            .map { it.toSearchResponse() }
    }

    // ============================================================
    // load
    // ============================================================

    override suspend fun load(url: String): LoadResponse {
        val id = url.split("/").last().toIntOrNull()
            ?: throw ErrorLoadingException("Invalid id: $url")

        val html = get("$mainUrl/synopsis/$id")
        val doc = Jsoup.parse(html)

        val donghua = astroIsland(html, "SynopsisActions")
            ?.nested("donghua")
            ?: throw ErrorLoadingException("donghua island not found")

        val title   = donghua.str("title")
        val poster  = donghua.str("poster_url")
        val type    = if (donghua.str("type").contains("Movie", ignoreCase = true))
            TvType.Movie else TvType.Anime
        val status  = when (donghua.str("status")) {
            "On-Going"  -> ShowStatus.Ongoing
            "Completed" -> ShowStatus.Completed
            else        -> null
        }
        val synopsis = jldDescription(html)
            ?: doc.selectFirst("div.whitespace-pre-wrap")?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")

        val episodeGrid = astroIsland(html, "EpisodeGrid")
        val donghuaId   = episodeGrid?.int("donghuaId") ?: id
        val episodes    = (episodeGrid?.list("episodes") ?: emptyList()).mapNotNull { ep ->
            val epNum = ep.int("ep_number").takeIf { it > 0 } ?: return@mapNotNull null
            newEpisode(ep) {
                this.name    = "Episode $epNum"
                this.episode = epNum
                this.data    = "$mainUrl/episode/$donghuaId/$epNum"
                this.posterUrl = poster
            }
        }

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            this.posterUrl  = poster
            this.plot       = synopsis
            this.showStatus = status
        }
    }

    // ============================================================
    // loadLinks
    // ============================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("YunshanID", "=== loadLinks: $data ===")

        val html = get(data)
        val serverUrls = (astroIsland(html, "WatchClient")?.list("servers") ?: emptyList())
            .mapNotNull { it.str("embed_url").takeIf { u -> u.isNotBlank() } }

        Log.d("YunshanID", "Servers: ${serverUrls.size}")

        if (serverUrls.isEmpty()) {
            Log.e("YunshanID", "Tidak ada server URL!")
            return false
        }

        val driveIdRegex = Regex("/file/d/([a-zA-Z0-9_-]{10,})")
        val seenDriveIds = mutableSetOf<String>()
        val seenUrls     = mutableSetOf<String>()
        val referer      = mainUrl

        serverUrls.forEach { embed ->
            when {
                embed.contains("drive.google.com") -> {
                    val fileId = driveIdRegex.find(embed)?.groupValues?.get(1)
                    if (fileId != null && seenDriveIds.add(fileId)) {
                        GdriveExtractor().getUrl(embed, "$referer/", subtitleCallback, callback)
                    }
                }
                else -> {
                    if (seenUrls.add(embed)) {
                        loadExtractor(embed, "$referer/", subtitleCallback, callback)
                    }
                }
            }
        }

        return true
    }

    // ============================================================
    // Scrapers
    // ============================================================

    private data class Card(
        val id: Int,
        val title: String,
        val poster: String?,
        val latestEp: Int = 0,
        val viewCount: Int = 0,
        val status: String? = null,
        val type: String? = null
    )

    private fun Card.toSearchResponse(): SearchResponse {
        val tvType = if (type?.contains("Movie", ignoreCase = true) == true)
            TvType.Movie else TvType.Anime
        val tag = when (status) {
            "Completed" -> " (Completed)"
            "On-Going"  -> " (Ongoing)"
            else        -> ""
        }
        val posterValue = poster
        val latestValue = latestEp
        return newAnimeSearchResponse("$title$tag", "$mainUrl/synopsis/$id", tvType) {
            this.posterUrl = posterValue
            addSub(latestValue)
        }
    }

    /** Parse the HomeGrid islands (latest / series / movie) from the homepage. */
    private suspend fun scrapeHome(): List<Card> {
        val html = get(mainUrl)
        val out = mutableListOf<Card>()

        for (island in islandTags(html, "HomeGrid")) {
            val props = decodeProps(island) ?: continue
            val root  = astroValue(props) as? Map<String, Any?> ?: continue
            val items = root.list("items")
            out += items.mapNotNull { item ->
                val id = item.int("id").takeIf { it > 0 } ?: return@mapNotNull null
                Card(
                    id        = id,
                    title     = item.str("title"),
                    poster    = item.str("poster_url").takeIf { it.isNotBlank() },
                    latestEp  = item.int("latest_ep"),
                    viewCount = item.int("view_count"),
                    status    = item.str("status").takeIf { it.isNotBlank() },
                    type      = item.str("type").takeIf { it.isNotBlank() }
                )
            }
        }
        return out.distinctBy { it.id }
    }

    /** Parse the full catalog from /jadwal (plain SSR HTML cards). */
    private suspend fun scrapeJadwal(): List<Card> {
        val html = get("$mainUrl/jadwal")
        val doc = Jsoup.parse(html)
        val out = mutableListOf<Card>()

        doc.select("a[href^=/synopsis/]").forEach { card ->
            val id = card.attr("href").split("/").last().toIntOrNull()
                ?: return@forEach
            val img  = card.selectFirst("img")
            val title = img?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: card.ownText().trim()
            if (title.isBlank()) return@forEach
            out += Card(
                id     = id,
                title  = title,
                poster = img?.attr("src")?.takeIf { it.startsWith("http") }
            )
        }
        return out.distinctBy { it.id }
    }

    /** Full catalog with status/type resolved by fetching each synopsis page (parallel). */
    private suspend fun scrapeFull(): List<Card> {
        val ids = scrapeJadwal().map { it.id }
        return coroutineScope {
            ids.map { id ->
                async { runCatching { synopsisOf(id) }.getOrNull() }
            }.awaitAll().filterNotNull()
        }.distinctBy { it.id }
    }

    /** Resolve one synopsis page into a Card (status/type/title/poster/latest ep). */
    private suspend fun synopsisOf(id: Int): Card? {
        return try {
            val html = get("$mainUrl/synopsis/$id")
            val donghua = astroIsland(html, "SynopsisActions")?.nested("donghua")
                ?: return null
            val epCount = astroIsland(html, "EpisodeGrid")?.list("episodes")?.size ?: 0
            Card(
                id       = id,
                title    = donghua.str("title"),
                poster   = donghua.str("poster_url").takeIf { it.isNotBlank() },
                latestEp = epCount,
                status   = donghua.str("status").takeIf { it.isNotBlank() },
                type     = donghua.str("type").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            Log.w("YunshanID", "synopsisOf failed for id=$id: ${e.message}")
            null
        }
    }

    // ============================================================
    // Astro island + props helpers
    // ============================================================

    /** Returns the full `<astro-island ...>` tag whose component-url contains [component]. */
    private fun islandTags(html: String, component: String): List<String> {
        val regex = Regex(
            "<astro-island\\b(?:(?!</astro-island>).)*?\\bcomponent-url=\"[^\"]*$component[^\"]*\"(?:(?!</astro-island>).)*?(?:/>|</astro-island>)",
            RegexOption.DOT_MATCHES_ALL
        )
        return regex.findAll(html).map { it.value }.toList()
    }

    private fun decodeProps(island: String): String? =
        Regex("props=\"([^\"]*)\"").find(island)?.groupValues?.get(1)
            ?.let { Parser.unescapeEntities(it, false) }

    /** Parse one astro-island's props into a Kotlin Map (or raw value). */
    private fun astroIsland(html: String, component: String): Map<String, Any?>? =
        islandTags(html, component).firstNotNullOfOrNull { island ->
            decodeProps(island)?.let { astroValue(it) as? Map<String, Any?> }
        }

    /** Extract the description from the page's JSON-LD script, if present. */
    private fun jldDescription(html: String): String? {
        val m = Regex("<script[^>]*application/ld\\+json[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        return (astroValue(m.groupValues[1]) as? Map<String, Any?>)?.str("description")
            ?.takeIf { it.isNotBlank() }
    }

    // ---- mini Astro-serialization parser ----
    private fun astroValue(raw: String): Any? = AstroParser(raw).parse()

    private class AstroParser(private val s: String) {
        private var i = 0
        private val refs = mutableListOf<Any?>()

        fun parse(): Any? = value()

        private fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun peek(): Char {
            ws()
            return if (i < s.length) s[i] else '\u0000'
        }

        private fun value(): Any? {
            val c = peek()
            return when {
                c == '{' -> obj()
                c == '[' -> arr()
                c == '"' -> str()
                c in "0123456789-" -> num()
                s.startsWith("true", i) -> { i += 4; true }
                s.startsWith("false", i) -> { i += 5; false }
                s.startsWith("null", i) -> { i += 4; null }
                else -> throw ErrorLoadingException("astro: unexpected char '$c'")
            }
        }

        private fun obj(): MutableMap<String, Any?> {
            i++
            val out = mutableMapOf<String, Any?>()
            if (peek() == '}') { i++; return out }
            while (true) {
                val k = str()
                if (peek() != ':') throw ErrorLoadingException("astro: obj colon")
                i++
                out[k] = value()
                when (val c = peek()) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw ErrorLoadingException("astro: obj, got '$c'")
                }
            }
        }

        private fun arr(): Any? {
            i++
            ws()
            if (i < s.length && s[i] in "0123456789") {
                // tagged: [0,val] , [1,[...]] , [2,ref]
                val digitsStart = i
                while (i < s.length && s[i].isDigit()) i++
                val tag = s.substring(digitsStart, i).toInt()
                ws()
                if (peek() != ',') throw ErrorLoadingException("astro: arr comma")
                i++
                if (tag == 2) {
                    val refIdx = (num() as Number).toInt()
                    ws()
                    if (peek() != ']') throw ErrorLoadingException("astro: ref close")
                    i++
                    return refs.getOrNull(refIdx)
                }
                val v = value()
                if (tag == 0) refs.add(v)
                ws()
                if (peek() != ']') throw ErrorLoadingException("astro: tagged close")
                i++
                return v
            } else {
                // raw JSON array
                val out = mutableListOf<Any?>()
                if (peek() == ']') { i++; return out }
                while (true) {
                    out.add(value())
                    when (val c = peek()) {
                        ',' -> i++
                        ']' -> { i++; return out }
                        else -> throw ErrorLoadingException("astro: raw arr, got '$c'")
                    }
                }
            }
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> { i++; return sb.toString() }
                    c == '\\' && i + 1 < s.length -> {
                        val nxt = s[i + 1]
                        when (nxt) {
                            'u' -> {
                                val hex = s.substring(i + 2, i + 6)
                                sb.append(hex.toInt(16).toChar())
                                i += 6
                            }
                            else -> {
                                sb.append(nxt)
                                i += 2
                            }
                        }
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            throw ErrorLoadingException("astro: unterminated string")
        }

        private fun num(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '.')) i++
            val text = s.substring(start, i)
            return text.toIntOrNull() ?: text.toDoubleOrNull() ?: text
        }
    }
}

// ---- typed accessors over Any? maps/lists ----
private fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>
private fun Map<String, Any?>.str(key: String): String = this[key] as? String ?: ""
private fun Map<String, Any?>.int(key: String): Int = when (val v = this[key]) {
    is Int    -> v
    is Long   -> v.toInt()
    is Double -> v.toInt()
    is String -> v.toIntOrNull() ?: 0
    else      -> 0
}
private fun Map<String, Any?>.list(key: String): List<Map<String, Any?>> =
    (this[key] as? List<*>)?.mapNotNull { it.asMap() } ?: emptyList()
private fun Map<String, Any?>.nested(key: String): Map<String, Any?>? = this[key]?.asMap()
