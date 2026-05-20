package com.Kuramanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
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
        "properties/status/ongoing" to "Anime Ongoing",
        "properties/status/finished_airing" to "Anime Selesai",
        "anime?order_by=latest" to "Update Terbaru",
        "anime?order_by=latest&type=episode" to "Rilisan Episode Terbaru",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.contains("type=episode") -> if (page == 1) mainUrl else "$mainUrl?page=$page"
            request.data.contains("?") -> "$mainUrl/${request.data}&page=$page"
            else -> "$mainUrl/${request.data}?page=$page"
        }

        val document = app.get(url, headers = browserHeaders).document

        val items = if (request.data.contains("type=episode")) {
            document.select(".product__sidebar__comment__item").mapNotNull { it.toEpisodeSearchResult() }
        } else {
            document.select("div.product__item").mapNotNull { it.toSearchResult() }
        }

        val hasNext = document.selectFirst("a.page__link i.fa-angle-right") != null ||
                      document.select("div.product__pagination a").any { it.text().trim() == (page + 1).toString() }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val aTag = selectFirst("h5 a") ?: selectFirst("a")!!
        val title = aTag.text().trim()
        val href = fixUrl(aTag.attr("href"))
        val posterUrl = selectFirst(".product__item__pic")?.attr("data-setbg")
        val typeText = selectFirst(".product__item__text ul li")?.text()?.lowercase()
        val type = if (typeText?.contains("movie") == true) TvType.Movie else TvType.Anime

        val epText = selectFirst(".ep")?.text()?.trim()

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            addDubStatus(DubStatus.Subbed, epText?.filter { it.isDigit() }?.toIntOrNull())
        }
    }

    private fun Element.toEpisodeSearchResult(): SearchResponse {
        val aTag = selectFirst("h5 a")!!
        val title = aTag.ownText().trim()
        val href = fixUrl(aTag.attr("href"))
        val posterUrl = selectFirst(".product__sidebar__comment__item__pic")?.attr("data-setbg")
        val epText = selectFirst(".comment__info span")?.text()?.trim() // "Episode 11"

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(DubStatus.Subbed, epText?.filter { it.isDigit() }?.toIntOrNull())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?search=$query"
        val document = app.get(url, headers = browserHeaders).document
        return document.select("div.product__item").map { it.toSearchResult() }
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

    private suspend fun getSiteConfig(): SiteConfig? {
        try {
            val arcSignal = app.get("$mainUrl/assets/js/arc-signal.min.js", headers = browserHeaders).text
            val jsFileVar = Regex("""f\s*=\s*"([^"]+)"""").find(arcSignal)?.groupValues?.get(1) ?: return null

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

    private suspend fun getAccessToken(config: SiteConfig): String? {
        val url = "$mainUrl/${config.prefix}${config.route}"
        val headers = browserHeaders + mapOf(
            "X-Fuck-ID" to "${config.key}:${config.token}",
            "X-Request-ID" to generateRandomString(6),
            "X-Request-Index" to "0"
        )
        return app.get(url, headers = headers).text.trim()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders).document
        val title = document.selectFirst(".anime__details__title h3")?.text()?.trim() ?: ""
        val poster = document.selectFirst(".anime__details__pic")?.attr("data-setbg")
        val plot = document.selectFirst("#synopsisField")?.text()?.trim()
        val genres = document.select(".breadcrumb__links__v2__tags a").map { it.text().trim().replace(",", "") }

        val statusText = document.select(".anime__details__widget ul li:contains(Status)").text().lowercase()
        val status = when {
            statusText.contains("ongoing") || statusText.contains("tayang") -> ShowStatus.Ongoing
            statusText.contains("finished") || statusText.contains("selesai") -> ShowStatus.Completed
            else -> null
        }

        val epPopover = document.selectFirst("#episodeLists")
        val episodes = mutableListOf<Episode>()

        if (epPopover != null) {
            val content = epPopover.attr("data-content")
            val epDoc = Jsoup.parse(content)
            episodes.addAll(parseEpisodes(epDoc))

            // Check for more pages
            var page = 2
            while (true) {
                val pageUrl = if (url.contains("?")) "$url&page=$page" else "$url?page=$page"
                val pageDoc = app.get(pageUrl, headers = browserHeaders).document
                val nextPagePopover = pageDoc.selectFirst("#episodeLists")
                if (nextPagePopover != null) {
                    val nextContent = nextPagePopover.attr("data-content")
                    val nextEpDoc = Jsoup.parse(nextContent)
                    val newEpisodes = parseEpisodes(nextEpDoc)
                    if (newEpisodes.isEmpty() || episodes.any { it.data == newEpisodes.first().data }) break
                    episodes.addAll(newEpisodes)
                    page++
                } else {
                    break
                }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data }.sortedBy { it.episode })
        }
    }

    private fun parseEpisodes(doc: Element): List<Episode> {
        return doc.select("a.btn-danger").mapNotNull {
            val href = it.attr("href")
            val text = it.text().trim()
            val epNum = Regex("""Ep\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(href) {
                this.episode = epNum
                this.name = "Episode $epNum"
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val config = getSiteConfig() ?: return false
        val token = getAccessToken(config) ?: return false

        val document = app.get(data, headers = browserHeaders).document
        val servers = document.select("#changeServer option").map { it.attr("value") }

        servers.forEach { server ->
            val playerUrl = "$data?${config.pageTokenKey}=$token&${config.streamServerKey}=$server&page=1"
            val playerDoc = app.get(playerUrl, headers = browserHeaders).document

            // Standard extraction
            if (server == "kuramadrive") {
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
            } else {
                playerDoc.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src")
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
