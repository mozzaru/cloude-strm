package com.Animexin

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Animexin : MainAPI() {
    override var mainUrl              = "https://animexin.dev"
    override var name                 = "Animexin"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.Anime)

    // Matches the site's own navigation menu (verified live 2026-08).
    // NOTE: `type=Movie` is case-sensitive in this theme, `order=popular` must
    // not be duplicated, and pagination uses the `page` query parameter.
    override val mainPage = mainPageOf(
        "anime/?status=ongoing&order=update" to "Recently Updated",
        "anime/?status=ongoing&order=popular" to "Popular",
        "anime/?status=&type=&order=update" to "Donghua",
        "anime/?status=&type=Movie&order=update" to "Movies",
        "anime/?sub=raw" to "Anime (RAW)",
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Upgrade-Insecure-Requests" to "1",
    )

    companion object {
        private const val TAG = "Animexin"
        private val cfSolveMutex = Mutex()
    }

    // ==== Cloudflare bypass ====
    // animexin.dev serves the root from cache but puts a managed challenge
    // (`cf-mitigated: challenge`, 403) on every subpage. CloudStream's app-level
    // CloudflareKiller is not part of the extension API, so we replicate it:
    // solve the challenge in a WebView (same as the browser auto-verify), read
    // cf_clearance from CookieManager and replay with the WebView user agent.
    // android.webkit is only touched via reflection so the JVM (cross-platform)
    // build stays happy; there it degrades to a plain request.

    private fun cfClearanceCookie(url: String): String? = try {
        val manager = Class.forName("android.webkit.CookieManager")
            .getMethod("getInstance").invoke(null)
        val cookie = manager.javaClass
            .getMethod("getCookie", String::class.java).invoke(manager, url) as? String
        cookie?.split(";")
            ?.mapNotNull { part ->
                val split = part.trim().split("=", limit = 2)
                if (split.size == 2) split[0] to split[1] else null
            }
            ?.firstOrNull { it.first == "cf_clearance" }?.second
    } catch (_: Throwable) {
        null
    }

    private suspend fun solveCloudflare(url: String) = cfSolveMutex.withLock {
        if (cfClearanceCookie(url) != null) return@withLock
        Log.d(TAG, "Cloudflare challenge detected, opening WebView: $url")
        try {
            WebViewResolver(
                interceptUrl = Regex(".^"),
                userAgent = null,
                useOkhttp = false,
                additionalUrls = listOf(Regex("."))
            ).resolveUsingWebView(url) {
                cfClearanceCookie(url) != null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "WebView cloudflare solve failed: ${e.message}")
        }
    }

    private fun isChallengeResponse(code: Int, server: String?): Boolean {
        if (code != 403 && code != 503) return false
        return server?.contains("cloudflare", ignoreCase = true) == true
    }

    private fun cloudflareHeaders(url: String): Map<String, String> {
        val clearance = cfClearanceCookie(url) ?: return emptyMap()
        val headers = mutableMapOf("Cookie" to "cf_clearance=$clearance")
        WebViewResolver.webViewUserAgent?.let { headers["user-agent"] = it }
        return headers
    }

    private suspend fun getDocument(url: String): Document {
        var res = app.get(url, headers = browserHeaders + cloudflareHeaders(url))
        if (isChallengeResponse(res.code, res.headers["server"])) {
            solveCloudflare(res.url)
            res = app.get(url, headers = browserHeaders + cloudflareHeaders(url))
            if (isChallengeResponse(res.code, res.headers["server"])) {
                throw ErrorLoadingException("Cloudflare challenge tidak terpecahkan untuk $url")
            }
        }
        return res.document
    }

    // ==== Main page ====

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/${request.data}"
        else "$mainUrl/${request.data}&page=$page"
        val document = getDocument(url)

        val home = document.select("div.listupd article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = hasNextPage(document)
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("div.bsx > a") ?: return null
        val href = fixUrl(aTag.attr("href"))
        val title = selectFirst("div.tt")?.ownText()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: aTag.attr("title").ifBlank { aTag.text() }
        val poster = extractPoster(aTag)
        val typeText = selectFirst(".typez")?.text()?.trim() ?: ""
        val tvType = if (typeText.contains("movie", ignoreCase = true)) TvType.Movie else TvType.Anime
        val epNum = Regex("\\d+").find(selectFirst("span.epx")?.text().orEmpty())?.value?.toIntOrNull()

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            addDubStatus(DubStatus.Subbed, epNum)
        }
    }

    private fun extractPoster(aTag: Element): String? {
        val img = aTag.selectFirst("img") ?: return null
        val raw = img.attr("src").ifBlank { img.attr("data-src") }
        if (raw.isBlank()) return null
        return fixUrlNull(raw)
    }

    private fun hasNextPage(doc: Document): Boolean {
        if (doc.selectFirst("div.hpage a.r") != null) return true
        if (doc.selectFirst("a.next.page-numbers") != null) return true
        if (doc.selectFirst("link[rel=next]") != null) return true
        return false
    }

    // ==== Search ====

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = query.trim().replace(" ", "+")
        val url = if (page == 1) "$mainUrl/?s=$encoded"
        else "$mainUrl/page/$page/?s=$encoded"
        val document = getDocument(url)
        return document.select("div.listupd article")
            .mapNotNull { it.toSearchResult() }
            .toNewSearchResponseList()
    }

    // ==== Load ====

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("div.thumb img")?.attr("src")
            ?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val speText = document.selectFirst(".spe")?.text().orEmpty()
        val episodeItems = document.select("div.eplister ul li")

        val isMovie = speText.contains("Movie", ignoreCase = true) ||
                (episodeItems.size == 1 && episodeItems.first()
                    ?.selectFirst("div.epl-num")?.text()
                    ?.contains("Movie", ignoreCase = true) == true)

        if (isMovie) {
            // The series page only links to the actual watch page, which holds
            // the .mobius servers. Pass that page to loadLinks().
            val watchUrl = episodeItems.first()?.selectFirst("a")?.attr("href")
                ?.takeIf { it.isNotBlank() } ?: url
            return newMovieLoadResponse(title, url, TvType.Movie, fixUrl(watchUrl)) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        val episodeRegex = Regex("(\\d+)")
        val seenHrefs = mutableSetOf<String>()
        val episodes = episodeItems.mapNotNull { info ->
            val href1 = info.selectFirst("a")?.attr("href")
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val fixedHref = fixUrl(href1)
            if (!seenHrefs.add(fixedHref)) return@mapNotNull null
            val posterr = info.selectFirst("a img")?.attr("src").orEmpty()
            val epText = info.selectFirst("div.epl-num")?.text()?.trim().orEmpty()
            val epnum = episodeRegex.find(epText)?.groupValues?.get(1)?.toIntOrNull()
            val epTitle = info.selectFirst("div.epl-title")?.text()?.trim()?.ifBlank { null }

            newEpisode(fixedHref) {
                this.episode = epnum
                this.name = epTitle ?: epnum?.let { "Episode $it" } ?: epText
                this.posterUrl = posterr
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ==== Links ====

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDocument(data)
        val servers = document.select(".mobius option")
        Log.d(TAG, "loadLinks: found ${servers.size} servers for $data")

        for (server in servers) {
            try {
                val base64 = server.attr("value")
                if (base64.isBlank()) continue
                val decoded = base64Decode(base64)
                val href = Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
                    ?.takeIf { it.isNotBlank() } ?: continue
                val url = httpsify(href)
                Log.d(TAG, "loadLinks: server '${server.text().trim()}' -> $url")
                loadExtractor(url, data, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.w(TAG, "loadLinks: server failed: ${e.message}")
            }
        }
        return true
    }
}
