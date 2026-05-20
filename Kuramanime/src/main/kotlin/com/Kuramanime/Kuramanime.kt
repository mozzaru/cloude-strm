package com.Kuramanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Kuramanime : MainAPI() {
    override var mainUrl = "https://v18.kuramanime.ing"
    override var name = "Kuramanime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "anime?order_by=latest&status=ongoing&country=jp" to "Anime Sedang Tayang",
        "anime?order_by=latest&status=ongoing&country=cn" to "Donghua Sedang Tayang",
        "anime?order_by=latest&status=finished_airing&country=jp" to "Anime Selesai Tayang",
        "anime?order_by=latest&status=finished_airing&country=cn" to "Donghua Selesai Tayang",
        "quick/movie?order_by=latest" to "Film Layar Lebar",
        "anime?order_by=latest&country=jp" to "Anime Jepang Terbaru",
        "anime?order_by=latest&country=cn" to "Donghua Terbaru",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("?")) {
            "$mainUrl/${request.data}&page=$page"
        } else {
            "$mainUrl/${request.data}?page=$page"
        }

        val document = app.get(url, headers = browserHeaders).document
        val items = document.select("div.product__item").mapNotNull { it.toSearchResult() }

        val hasNext = document.select("div.product__pagination a").any {
            it.text().trim().equals("Next", ignoreCase = true) ||
            it.selectFirst("i.fa-angle-right") != null ||
            it.text().trim() == (page + 1).toString()
        }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("h5 a") ?: selectFirst("a") ?: return null
        val title = aTag.text().trim()
        val href = fixUrl(aTag.attr("href"))
        val posterUrl = selectFirst(".product__item__pic")?.attr("data-setbg") ?: selectFirst(".product__item__pic")?.attr("src")
        val typeText = selectFirst(".product__item__text ul li")?.text()?.lowercase()
        val type = if (typeText?.contains("movie") == true) TvType.Movie else TvType.Anime

        val epText = selectFirst(".ep")?.text()?.trim()

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            addDubStatus(DubStatus.Subbed, epText?.filter { it.isDigit() }?.toIntOrNull())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?search=$query&order_by=latest"
        val document = app.get(url, headers = browserHeaders).document
        return document.select("div.product__item").mapNotNull { it.toSearchResult() }
    }

    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }

    data class SiteConfig(
        val prefix: String,
        val route: String,
        val key: String,
        val token: String,
        val pageTokenKey: String,
        val streamServerKey: String
    )

    private suspend fun getSiteConfig(doc: Document): SiteConfig? {
        try {
            val jsFileVar = doc.selectFirst("[data-kk]")?.attr("data-kk") ?: "wzl3ClXO8shDECR"
            val jsConfig = app.get("$mainUrl/assets/js/$jsFileVar.js", headers = browserHeaders).text

            val prefix = Regex("""MIX_PREFIX_AUTH_ROUTE_PARAM:\s*'([^']+)'""").find(jsConfig)?.groupValues?.get(1) ?: ""
            val route = Regex("""MIX_AUTH_ROUTE_PARAM:\s*'([^']+)'""").find(jsConfig)?.groupValues?.get(1) ?: ""
            val key = Regex("""MIX_AUTH_KEY:\s*'([^']+)'""").find(jsConfig)?.groupValues?.get(1) ?: ""
            val token = Regex("""MIX_AUTH_TOKEN:\s*'([^']+)'""").find(jsConfig)?.groupValues?.get(1) ?: ""
            val pageTokenKey = Regex("""MIX_PAGE_TOKEN_KEY:\s*'([^']+)'""").find(jsConfig)?.groupValues?.get(1) ?: ""
            val streamServerKey = Regex("""MIX_STREAM_SERVER_KEY:\s*'([^']+)'""").find(jsConfig)?.groupValues?.get(1) ?: ""

            return SiteConfig(prefix, route, key, token, pageTokenKey, streamServerKey)
        } catch (e: Exception) {
            return null
        }
    }

    private suspend fun getAccessToken(config: SiteConfig, referer: String): String? {
        val url = "$mainUrl/${config.prefix}${config.route}"
        val headers = browserHeaders + mapOf(
            "X-Fuck-ID" to "${config.key}:${config.token}",
            "X-Request-ID" to generateRandomString(6),
            "X-Request-Index" to "0",
            "Referer" to referer,
            "X-Requested-With" to "XMLHttpRequest"
        )
        return app.get(url, headers = headers).text.trim()
    }

    override suspend fun load(url: String): LoadResponse? {
        val seriesUrl = when {
            url.contains("/episode/") -> url.substringBefore("/episode/")
            url.contains("/batch/") -> url.substringBefore("/batch/")
            else -> url
        }

        val document = app.get(seriesUrl, headers = browserHeaders).document
        val title = document.selectFirst(".anime__details__title h3")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".anime__details__pic")?.attr("data-setbg") ?:
                     document.selectFirst(".anime__details__pic")?.attr("src") ?:
                     document.selectFirst("meta[property=\"og:image\"]")?.attr("content") ?:
                     document.selectFirst(".anime__details__pic img")?.attr("src")

        val plot = document.selectFirst("#synopsisField")?.text()?.trim() ?:
                   document.selectFirst(".anime__details__text p")?.text()?.trim() ?:
                   document.selectFirst("meta[property=\"og:description\"]")?.attr("content")

        val genres = document.select(".anime__details__widget ul li").find { it.text().contains("Genre", ignoreCase = true) }?.select("a")?.map { it.text().trim().replace(",", "") }?.filter { it.isNotEmpty() } ?:
                     document.select(".breadcrumb__links__v2__tags a").map { it.text().trim().replace(",", "") }.filter { it.isNotEmpty() }

        val statusText = document.select(".anime__details__widget ul li").find { it.text().contains("Status", ignoreCase = true) }?.text()?.lowercase() ?: ""
        val status = when {
            statusText.contains("ongoing") || statusText.contains("tayang") -> ShowStatus.Ongoing
            statusText.contains("finished") || statusText.contains("selesai") -> ShowStatus.Completed
            else -> null
        }

        val episodes = mutableListOf<Episode>()

        fun parseFromDoc(doc: Element) {
            // Try grid buttons
            doc.select("#animeEpisodes a.ep-button").forEach {
                val epHref = fixUrl(it.attr("href"))
                val epText = it.text().trim()
                val epNum = Regex("""Ep\s*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                if (episodes.none { e -> e.data == epHref }) {
                    episodes.add(newEpisode(epHref) {
                        this.episode = epNum
                        this.name = "Episode $epNum"
                    })
                }
            }

            // Try popover
            val popover = doc.selectFirst("#episodeLists")
            if (popover != null) {
                val content = popover.attr("data-content")
                val epDoc = Jsoup.parse(content)
                epDoc.select("a.btn-danger, a.btn-secondary").forEach {
                    val epHref = fixUrl(it.attr("href"))
                    val epText = it.text().trim()
                    if (epHref.contains("/batch/")) return@forEach
                    val epNum = Regex("""Ep\s*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                    if (episodes.none { e -> e.data == epHref }) {
                        episodes.add(newEpisode(epHref) {
                            this.episode = epNum
                            this.name = "Episode $epNum"
                        })
                    }
                }
            }
        }

        // Initial parse
        parseFromDoc(document)

        // Pagination for episodes
        var currentPage = 2
        while (true) {
            val pageUrl = if (seriesUrl.contains("?")) "$seriesUrl&page=$currentPage" else "$seriesUrl?page=$currentPage"
            val pageDoc = app.get(pageUrl, headers = browserHeaders).document
            val beforeCount = episodes.size
            parseFromDoc(pageDoc)
            if (episodes.size == beforeCount || currentPage > 60) break
            currentPage++
        }

        return newAnimeLoadResponse(title, seriesUrl, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data }.sortedBy { it.episode })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = browserHeaders).document
        val config = getSiteConfig(document) ?: return false
        val token = getAccessToken(config, data) ?: return false

        val servers = document.select("#changeServer option").map { it.attr("value") }

        val ajaxHeaders = browserHeaders + mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to data
        )

        servers.forEach { server ->
            val playerUrl = "$data?${config.pageTokenKey}=$token&${config.streamServerKey}=$server&page=1"
            val playerDoc = app.get(playerUrl, headers = ajaxHeaders).document

            // Standard extraction
            val hlsSrc = playerDoc.selectFirst("#player")?.attr("data-hls-src")
            if (hlsSrc != null) {
                callback.invoke(
                    newExtractorLink(
                        source = "Kuramadrive S1",
                        name = "Kuramadrive S1",
                        url = hlsSrc,
                        type = ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.Unknown.value
                        this.referer = playerUrl
                    }
                )
            }

            playerDoc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotEmpty()) {
                    loadExtractor(src, playerUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
