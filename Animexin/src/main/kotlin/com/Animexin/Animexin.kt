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
import java.net.URLEncoder

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
        "Sec-Fetch-Site" to "none",
        "Upgrade-Insecure-Requests" to "1",
    )

    companion object {
        private const val TAG = "Animexin"
        private val cfSolveMutex = Mutex()
    }

    // ==== Cloudflare ====
    // Only the /anime/ listing pages sit behind a Cloudflare managed challenge
    // (`cf-mitigated: challenge`, 403): they pass silently in a real browser
    // (no user verification — which is why the site "has no Cloudflare" there),
    // but okhttp (`app.get`) gets 403 because Cloudflare fingerprints the TLS
    // stack, NOT just the User-Agent/cookies. So even replaying `cf_clearance`
    // obtained from a WebView over okhttp fails.
    //
    // The only reliable way to read the challenged page is therefore the
    // browser context itself: open a WebView, let the challenge auto-pass, then
    // capture the fully-rendered `outerHTML` and parse that (same as a browser).
    // We keep the okhttp replay only as a cheap first attempt that usually
    // succeeds once a clearance already exists for the domain.
    //
    // android.webkit / JS is only touched via reflection in the cookie helper so
    // the JVM (cross-platform) build stays happy and simply does the plain GET.

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

    private fun isChallengeResponse(code: Int, server: String?): Boolean =
        (code == 403 || code == 503) && server?.contains("cloudflare", ignoreCase = true) == true

    private fun looksLikeContentPage(html: String): Boolean =
        listOf("listupd", "entry-title", "eplister", "bsx").any { html.contains(it, ignoreCase = true) }

    private fun requestHeaders(url: String): Map<String, String> {
        val headers = browserHeaders.toMutableMap()
        val clearance = cfClearanceCookie(url) ?: return headers
        // cf_clearance is bound to the WebView UA. Overwrite the SAME key —
        // adding a differently-cased "user-agent" would send two UA headers
        // and Cloudflare rejects the clearance on mismatch.
        WebViewResolver.webViewUserAgent?.let { headers["User-Agent"] = it }
        headers["Cookie"] = "cf_clearance=$clearance"
        return headers
    }

    /**
     * Opens [url] in a WebView (the challenge auto-passes silently) and returns
     * the fully-rendered page HTML from the browser context. This bypasses the
     * TLS fingerprint check that makes an okhttp replay fail. Returns null if
     * the DOM was never fully captured (timeout / JVM build).
     */
    private suspend fun solveAndCapture(url: String): String? {
        val htmlRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
        return try {
            cfSolveMutex.withLock {
                Log.d(TAG, "Cloudflare challenge, menangkap lewat WebView: $url")
                WebViewResolver(
                    interceptUrl = Regex(".^"),
                    userAgent = null,
                    useOkhttp = false,
                    script = "document.documentElement.outerHTML;",
                    scriptCallback = { result ->
                        val decoded = AppUtils.tryParseJson<String>(result)
                        if (decoded != null && decoded.length > 1000 &&
                            decoded.trimEnd().endsWith("</html>") && looksLikeContentPage(decoded)
                        ) htmlRef.set(decoded)
                    },
                    additionalUrls = listOf(Regex("."))
                ).resolveUsingWebView(url) {
                    htmlRef.get() != null
                }
            }
            htmlRef.get()?.takeIf { it.trimEnd().endsWith("</html>") }
        } catch (e: Throwable) {
            Log.w(TAG, "WebView capture gagal: ${e.message}")
            null
        }
    }

    private suspend fun getDocument(url: String): Document {
        val res = app.get(url, headers = requestHeaders(url))
        if (!isChallengeResponse(res.code, res.headers["server"])) return res.document

        // okhttp is TLS-fingerprinted, so fall back to the WebView browser context.
        val html = solveAndCapture(res.url)
            ?: throw ErrorLoadingException("Blokir Cloudflare: tidak bisa memuat animexin.dev (bukanya di browser). $url")
        return Jsoup.parse(html)
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
        val raw = sequenceOf(
            img.attr("src"),
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("data-lazy"),
        ).firstOrNull { it.isNotBlank() } ?: return null
        // The theme ships resized "-768x1077" copies next to the full-size
        // image; stripping the size keeps the original full-quality poster.
        val fixed = fixUrlNull(raw) ?: return null
        return fixed.replace(Regex("(?:-\\d{2,4}x\\d{2,4})?(\\.[a-z0-9]+)$", RegexOption.IGNORE_CASE), "$1")
    }

    private fun hasNextPage(doc: Document): Boolean {
        if (doc.selectFirst("div.hpage a.r") != null) return true
        if (doc.selectFirst("a.next.page-numbers") != null) return true
        if (doc.selectFirst("link[rel=next]") != null) return true
        return false
    }

    // ==== Search ====

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page == 1) "$mainUrl/?s=$encoded"
        else "$mainUrl/page/$page/?s=$encoded"
        val document = getDocument(url)
        return document.select("div.listupd article")
            .mapNotNull { it.toSearchResult() }
            .toNewSearchResponseList()
    }

    // ==== Load ====

    // Listing items are series pages, but search (and old bookmarks) can hand us
    // episode posts like "...-episode-98-indonesia-english-sub/". Episode pages
    // have no eplister, so resolve them to their series page first.
    private fun toSeriesUrl(url: String): String {
        if (!url.contains("-episode-") && !url.contains("-subtitle-indonesia")) return url
        return url
            .replace(Regex("-episode-[^/]+/?$"), "/")
            .replace(Regex("-subtitle-indonesia/?$"), "/")
    }

    override suspend fun load(url: String): LoadResponse {
        val seriesUrl = toSeriesUrl(url)
        val document = getDocument(seriesUrl)
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("div.thumb img")?.let { img ->
            sequenceOf(
                img.attr("src"),
                img.attr("data-src"),
                img.attr("data-lazy-src"),
                img.attr("data-original"),
            ).firstOrNull { it.isNotBlank() }
        }?.let(::fixUrlNull)
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
            return newMovieLoadResponse(title, seriesUrl, TvType.Movie, fixUrl(watchUrl)) {
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

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.Anime, episodes.reversed()) {
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
