package com.donghub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64

class Donghub : MainAPI() {
    override var mainUrl = "https://donghub.vip"
    override var name = "Donghub"
    override val hasMainPage = true

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "id-ID,id;q=0.9",
        "Upgrade-Insecure-Requests" to "1",
        "Referer" to "https://www.google.com/",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"147\", \"Not.A/Brand\";v=\"8\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "cross-site",
        "Sec-Fetch-User" to "?1"
    )

    // ==== Cloudflare bypass ====
    // donghub.vip serves the root (and some cached pages) but puts a managed
    // challenge ("Just a moment", 403) on every subpage. The challenge document
    // has no eplister/player/Type: fields, so load() used to fall through to
    // TvType.Movie and every series showed up as a movie. Same fix as Animexin:
    // solve the challenge in a WebView and either capture the rendered HTML
    // straight out of it (primary, immune to okhttp TLS fingerprinting) or
    // replay with cf_clearance + WebView user agent. android.webkit is only
    // touched via reflection so the JVM (cross-platform) build stays happy.

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

    private val pageMarkers = listOf(
        "listupd", "entry-title", "eplister", "mobius", "bsx", "egghead", "synp"
    )

    private fun looksLikeChallenge(html: String): Boolean =
        html.contains("challenges.cloudflare.com", ignoreCase = true) ||
                html.contains("Just a moment", ignoreCase = true)

    private fun looksLikeContentPage(html: String): Boolean =
        pageMarkers.any { html.contains(it, ignoreCase = true) }

    private suspend fun solveCloudflareAndCapture(url: String): String? {
        val htmlRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
        return try {
            cfSolveMutex.withLock {
                Log.d(TAG, "Cloudflare challenge, opening WebView: $url")
                WebViewResolver(
                    interceptUrl = Regex(".^"),
                    userAgent = null,
                    useOkhttp = false,
                    script = "document.documentElement.outerHTML;",
                    scriptCallback = { result ->
                        val decoded = AppUtils.tryParseJson<String>(result)
                        if (decoded != null && decoded.length > 2000 &&
                            !looksLikeChallenge(decoded) && looksLikeContentPage(decoded)
                        ) htmlRef.set(decoded)
                    },
                    additionalUrls = listOf(Regex("."))
                ).resolveUsingWebView(url) {
                    cfClearanceCookie(url) != null &&
                            htmlRef.get()?.trimEnd()?.endsWith("</html>") == true
                }
            }
            htmlRef.get()?.takeIf { !looksLikeChallenge(it) && looksLikeContentPage(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "WebView cloudflare solve failed: ${e.message}")
            null
        }
    }

    private fun isChallengeResponse(code: Int, server: String?): Boolean {
        if (code != 403 && code != 503) return false
        return server?.contains("cloudflare", ignoreCase = true) == true
    }

    private fun posterHeaders(): Map<String, String> =
        mapOf("Referer" to "$mainUrl/")

    private fun requestHeaders(url: String): Map<String, String> {
        val headers = baseHeaders.toMutableMap()
        val clearance = cfClearanceCookie(url) ?: return headers
        // cf_clearance is bound to the WebView UA. Overwrite the SAME key —
        // adding a differently-cased "user-agent" would send two UA headers
        // and Cloudflare rejects the clearance on mismatch.
        WebViewResolver.webViewUserAgent?.let { headers["User-Agent"] = it }
        headers["Cookie"] = "cf_clearance=$clearance"
        return headers
    }

    private suspend fun getDocument(url: String): Document {
        var res = app.get(url, headers = requestHeaders(url))
        if (isChallengeResponse(res.code, res.headers["server"])) {
            val webHtml = solveCloudflareAndCapture(res.url)
            if (webHtml != null) return Jsoup.parse(webHtml)

            res = app.get(url, headers = requestHeaders(url))
            if (isChallengeResponse(res.code, res.headers["server"])) {
                throw ErrorLoadingException("Cloudflare challenge tidak terpecahkan untuk $url")
            }
        }
        return res.document
    }

    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "" to "Rilisan Terbaru",
        "popular-today" to "Populer Hari Ini",
        "anime/?order=popular" to "Populer",
        "anime/?status=ongoing&sub=&order=" to "Ongoing",
        "anime/?status=completed&type=" to "Completed",
        "anime/?status=&type=movie&order=" to "Movie"
    )

    //private val geoDmExtractor  = CustomGeoDailymotion()
    //private val dmExtractor     = CustomDailymotion()
    //private val megaExtractor   = MegaNzExtractor()
    //private val dtubeExtractor  = DtubeExtractor()

    private val episodeUrlRegex = Regex("""-episode-\d+""", RegexOption.IGNORE_CASE)

    private val indonesianMonths = mapOf(
        "januari" to "January", "februari" to "February", "maret" to "March",
        "april" to "April", "mei" to "May", "juni" to "June",
        "juli" to "July", "agustus" to "August", "september" to "September",
        "oktober" to "October", "november" to "November", "desember" to "December"
    )

    private fun parseIndonesianDate(raw: String): Long? {
        var normalized = raw.trim().lowercase()
        indonesianMonths.forEach { (id, en) -> normalized = normalized.replace(id, en) }
        return try {
            java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.ENGLISH)
                .parse(normalized.replaceFirstChar { it.uppercaseChar() })?.time
        } catch (_: Exception) { null }
    }

    private fun episodeUrlToSeriesUrl(epUrl: String): String? {
        val match = episodeUrlRegex.find(epUrl) ?: return null
        val basePath = epUrl.substring(0, match.range.first)
        return "$basePath/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "popular-today") {
            if (page > 1) return newHomePageResponse(
                list = HomePageList(name = request.name, list = emptyList(), isHorizontalImages = false),
                hasNext = false
            )
            val document = getDocument(mainUrl)
            val items = document.select("div.popconslide article").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(
                list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
                hasNext = false
            )
        }

        val url = when {
            request.data.isEmpty() -> if (page == 1) mainUrl else "$mainUrl/page/$page/"
            page == 1 -> "$mainUrl/${request.data}"
            else -> "$mainUrl/${request.data}&page=$page"
        }

        val document = getDocument(url)

        val items = if (request.data.isEmpty()) {
            val latestSection = document.select("div.bixbox").firstOrNull { box ->
                box.selectFirst("div.releases.latesthome") != null
            }
            latestSection?.select("article.bs")?.mapNotNull { it.toSearchResult() }
                ?: document.select("div.listupd article").mapNotNull { it.toSearchResult() }
        } else {
            document.select("div.listupd article").mapNotNull { it.toSearchResult() }
        }.distinctBy { it.url }

        val hasNext = document.selectFirst("div.hpage a.r") != null

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private suspend fun loadSeriesViaEpisode(episodeUrl: String): Pair<String, Document> {
        val firstDoc = getDocument(episodeUrl)
        val allEpsLink = firstDoc
            .selectFirst("div.naveps.bignav .nvs.nvsc a")?.attr("href")
        return if (allEpsLink != null) {
            val seriesUrl = fixUrl(allEpsLink)
            seriesUrl to getDocument(seriesUrl)
        } else {
            episodeUrl to firstDoc
        }
    }

    private fun extractEpNumFromText(text: String): Int? {
        return Regex("episode[- ](\\d+)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun isGenericTemplate(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("watch streaming") ||
               lower.contains("you can also download") ||
               lower.contains("don't forget to watch online") ||
               lower.contains("watch full episodes") ||
               lower.contains("english subbed on donghub") ||
               lower.contains("subtitle indonesia") ||
               lower.contains("subtitle indonesia hanya di") ||
               lower.contains("mp4 mkv hardsub softsub") ||
               lower.contains("360p") || lower.contains("480p") ||
               lower.contains("720p") || lower.contains("terabox") ||
               lower.contains("mirrored") ||
               text.length < 30
    }

    private fun isKeywordJunk(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("watch and download") ||
               (lower.contains("nonton") && lower.contains("download")) ||
               lower.contains("watch all the episodes") ||
               lower.contains("multi sub") ||
               Regex("episode\\s*\\d+\\s*(english sub|sub indo|subtitle)", RegexOption.IGNORE_CASE)
                   .containsMatchIn(text)
    }

    private fun isLangMarker(text: String): Boolean {
        val t = text.lowercase().trim()
        if (t.length > 15) return false
        return t == "english" || t == "eng" || t == "indonesia" || t == "indonesian" ||
               t == "indo" || t == "ind" || t == "bahasa indonesia"
    }

    private fun parseBilingualSynopsis(el: org.jsoup.nodes.Element): String {
        val indo = mutableListOf<String>()
        val eng = mutableListOf<String>()
        var current = "eng"

        el.select("h1, h2, h3, h4, p").forEach { child ->
            val t = child.text().trim()
            val tag = child.tagName()
            val cls = child.className().lowercase()

            val isReleasesHeading = cls.contains("releases") && t.length <= 60
            val looksLikeHeading = tag == "h1" || tag == "h2" || tag == "h3" || tag == "h4" || isReleasesHeading

            when {
                tag == "p" && isLangMarker(t) ->
                    current = if (t.lowercase().contains("indo")) "indo" else "eng"

                looksLikeHeading -> {
                    val heading = t.lowercase()
                    when {
                        heading.contains("indo") || heading.contains("indonesia") -> current = "indo"
                        heading.contains("english") || heading.contains(" eng") ||
                            heading.endsWith(" eng") || heading == "eng" -> current = "eng"
                    }
                }

                else -> {
                    if (t.isBlank() || isKeywordJunk(t) || isGenericTemplate(t)) return@forEach
                    if (current == "indo") indo.add(t) else eng.add(t)
                }
            }
        }

        if (indo.isEmpty() && eng.isEmpty()) return ""
        val pickIndo = indo.size >= eng.size && indo.isNotEmpty()
        val source = if (pickIndo) indo else eng
        return source.joinToString("\n\n").trim()
    }

    private fun cleanEpisodeTitle(rawTitle: String, seriesTitle: String, epNum: Int?): String {
        var clean = rawTitle

        if (seriesTitle.isNotBlank()) {
            clean = clean.replace(seriesTitle, "", ignoreCase = true)
        }

        clean = clean
            .replace(Regex("subtitle indonesia", RegexOption.IGNORE_CASE), "")
            .replace(Regex("sub indo", RegexOption.IGNORE_CASE), "")
            .trim { it == ' ' || it == '-' }
            .trim()

        return if (clean.length < 3) "Episode $epNum" else clean
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("div.bsx > a") ?: return null
        val rawTitle = aTag.attr("title").ifBlank {
            selectFirst("div.tt")?.ownText().orEmpty()
        }.ifBlank { aTag.text() }.trim()
        val href = fixUrl(aTag.attr("href"))
        val img = aTag.selectFirst("img")

        val posterUrlRaw = img?.run {
            attr("src").ifBlank { attr("data-src") }.ifBlank { attr("data-lazy-src") }
        }.orEmpty()

        val posterUrl = fixUrlNull(
            if (posterUrlRaw.startsWith("//")) "https:$posterUrlRaw" else posterUrlRaw
        )

        val isEggLayout = selectFirst("div.egghead") != null

        val eggTypeClass = selectFirst("div.eggtype")
            ?.classNames()?.firstOrNull { it != "eggtype" }
            ?.lowercase().orEmpty()
        val eggTypeLabel = selectFirst("div.eggtype")?.text()?.lowercase().orEmpty()
        val typeLabel = selectFirst(".typez")?.text()?.lowercase().orEmpty()
        val type = if (href.contains("/movie/", ignoreCase = true) ||
            typeLabel.contains("movie") ||
            eggTypeClass == "movie" ||
            eggTypeLabel.contains("movie")
        ) TvType.Movie else TvType.Anime

        val epNum: Int?
        val isCompleted: Boolean
        val isOngoing: Boolean
        val isHiatus: Boolean

        if (isEggLayout) {
            val eggEpText = selectFirst("div.eggepisode")?.text().orEmpty()
            epNum       = extractEpNumFromText(eggEpText)
            isCompleted = false
            isOngoing   = epNum != null
            isHiatus    = false
        } else {
            val epxText   = selectFirst("span.epx")?.text().orEmpty()
            val epxLower  = epxText.lowercase()
            val statusDiv = selectFirst("div.status")?.text()?.lowercase().orEmpty()

            isCompleted = "tamat"     in epxLower || "complete" in epxLower ||
                          "completed" in epxLower || "completed" in statusDiv
            isOngoing   = "ongoing"   in epxLower || "ongoing"  in statusDiv
            isHiatus    = "hiatus"    in epxLower || "hiatus"   in statusDiv

            val imgTitle = img?.attr("title").orEmpty()
            val aTitle   = aTag.attr("title").orEmpty()
            epNum = extractEpNumFromText(imgTitle)
                ?: extractEpNumFromText(aTitle)
                ?: extractEpNumFromText(href)
        }

        val statusSuffix = when {
            isCompleted -> " (Completed)"
            isHiatus    -> " (Hiatus)"
            isOngoing   -> " (Ongoing)"
            else        -> ""
        }

        val subLabel = selectFirst("span.sb")?.text()?.lowercase().orEmpty()

        return newAnimeSearchResponse("$rawTitle$statusSuffix", href, type) {
            this.posterUrl = posterUrl
            this.posterHeaders = posterHeaders()
            when {
                "sub" in subLabel -> addSub(epNum)
                "dub" in subLabel -> addDub(epNum)
                else              -> addSub(epNum)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDocument("$mainUrl/?s=$query")
        return document.select("div.listupd article").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        // Episode URLs follow "{slug}-episode-{n}-subtitle-indonesia/" — derive the
        // series URL directly so home/search clicks need only one request.
        // Some slugs are abbreviated in episode URLs (e.g. "-s5-" vs "-season-5"),
        // so the derived page is validated: if it isn't a real series page
        // (no episode list, no synopsis) we fall back to the "All Episodes" link.
        val derivedSeriesUrl = Regex(
            "(.*?)-episode-\\d+(?:-\\d+)?-subtitle-indonesia/?$",
            RegexOption.IGNORE_CASE
        ).find(url)?.groupValues?.get(1)?.let { fixUrl("$it/") }

        val seriesUrl: String
        val document: org.jsoup.nodes.Document

        if (derivedSeriesUrl != null) {
            val derivedDoc = try {
                getDocument(derivedSeriesUrl)
            } catch (e: Throwable) {
                Log.w(TAG, "Derived series page failed, falling back: ${e.message}")
                null
            }
            if (derivedDoc != null && derivedDoc.selectFirst("div.eplister, div.bixbox.synp") != null) {
                seriesUrl = derivedSeriesUrl
                document  = derivedDoc
            } else {
                val fallback = loadSeriesViaEpisode(url)
                seriesUrl   = fallback.first
                document    = fallback.second
            }
        } else {
            val fallback = loadSeriesViaEpisode(url)
            seriesUrl   = fallback.first
            document    = fallback.second
        }

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()

        var poster = document.selectFirst("div.ime > img")?.attr("src").orEmpty()
        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        }

        val synopsis = run {
            val synpEl = document.selectFirst("div.bixbox.synp div.entry-content")
            val synpText: String = if (synpEl != null) parseBilingualSynopsis(synpEl) else ""
            val descText    = document.selectFirst("div.desc")?.text()?.trim().orEmpty()
            val mindescText = document.selectFirst("div.mindesc")?.text()?.trim().orEmpty()
            val metaDesc    = document.selectFirst("meta[property=og:description]")
                ?.attr("content")?.trim().orEmpty()

            when {
                synpText.isNotBlank()    && !isGenericTemplate(synpText)    -> synpText
                descText.isNotBlank()    && !isGenericTemplate(descText)    -> descText
                mindescText.isNotBlank() && !isGenericTemplate(mindescText) -> mindescText
                metaDesc.isNotBlank()    && metaDesc != title &&
                    !isGenericTemplate(metaDesc) -> metaDesc
                else -> null
            }
        }

        val genres = document.select("div.genxed a").map { it.text().trim() }
    
        val statusRaw = document.select("div.spe span")
            .firstOrNull { it.text().startsWith("Status:") }
            ?.text()?.removePrefix("Status:")?.trim().orEmpty()
    
        val showStatus = when (statusRaw.lowercase()) {
            "ongoing"   -> ShowStatus.Ongoing
            "completed" -> ShowStatus.Completed
            else        -> null
        }

        val typeRaw = document.select("div.spe span")
            .firstOrNull { it.text().startsWith("Type:") }
            ?.text()?.removePrefix("Type:")?.trim().orEmpty()

        val episodeList = document.select("div.eplister ul li")
        val hasPlayer = extractDirectIframe(document) != null
        val isSeries  = episodeList.isNotEmpty()
        // The old `else -> TvType.Movie` fallback mislabeled every series as a
        // movie whenever the page could not be identified (e.g. a Cloudflare
        // challenge document). Only classify as Movie when there is actual
        // evidence, otherwise fail loudly so the challenge gets retried/surfaced.
        val looksLikeMovie = typeRaw.contains("movie", ignoreCase = true) ||
                (!isSeries && !hasPlayer && url.contains("movie", ignoreCase = true))
        val tvType = when {
            looksLikeMovie -> TvType.Movie
            typeRaw.isNotBlank() || isSeries || hasPlayer -> TvType.Anime
            else -> throw ErrorLoadingException(
                "Tidak dapat mengidentifikasi halaman: $seriesUrl"
            )
        }

        val episodes = if (isSeries) {
            episodeList.mapNotNull { li ->
                val a      = li.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrl(a.attr("href"))
                val epNum  = li.selectFirst("div.epl-num")?.text()?.trim()
                    ?.replace(Regex("""[^0-9]"""), "")?.toIntOrNull()
                val rawEpTitle = li.selectFirst("div.epl-title")?.text()?.trim().orEmpty()
                val cleanTitle = cleanEpisodeTitle(rawEpTitle, title, epNum)

                val rawDate = li.selectFirst("div.epl-date")?.text()?.trim()
                val epDate: Long? = rawDate?.let { parseIndonesianDate(it) }

                newEpisode(epHref) {
                    this.name      = cleanTitle
                    this.episode   = epNum
                    this.posterUrl = poster
                    this.date      = epDate
                }
            }.reversed()
        } else {
            listOf(newEpisode(url) {
                this.name = title.ifBlank { "Movie" }
                this.posterUrl = poster
            })
        }

        return newTvSeriesLoadResponse(title, seriesUrl, tvType, episodes) {
            this.posterUrl  = poster
            this.posterHeaders = posterHeaders()
            this.plot       = synopsis
            this.tags       = genres
            this.showStatus = showStatus
        }
    }

    private fun base64Decode(encoded: String): String {
        return try {
            val decodedBytes = Base64.getDecoder().decode(encoded.trim())
            String(decodedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Base64 decode failed: ${e.message}")
            ""
        }
    }

    private fun extractDirectIframe(doc: org.jsoup.nodes.Document): String? {
        val src = doc.selectFirst(".player-embed iframe")?.attr("src").orEmpty()
            .ifBlank { doc.selectFirst("iframe")?.attr("src").orEmpty() }
        if (src.isNotBlank()) {
            return if (src.startsWith("http")) src else "https:$src"
        }
        val firstOption = doc.selectFirst(".mobius option")
        val base64 = firstOption?.attr("value")?.trim()
        if (!base64.isNullOrBlank()) {
            try {
                val decoded = base64Decode(base64)
                val iframeSrc = Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
                if (!iframeSrc.isNullOrBlank()) {
                    return if (iframeSrc.startsWith("http")) iframeSrc else "https:$iframeSrc"
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private suspend fun resolveVideo(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            url.contains("geo.dailymotion.com") ->
                GeodailymotionFixed().getUrl(url, referer, subtitleCallback, callback)
            url.contains("dailymotion.com") ->
                DailymotionFixed().getUrl(url, referer, subtitleCallback, callback)
            else ->
                loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(TAG, "=== loadLinks called === URL: $data")

        val document = getDocument(data)

        val sources = mutableListOf<Pair<String, String>>()

        val directSrc = extractDirectIframe(document)
        if (directSrc != null) {
            Log.i(TAG, "Direct iframe: $directSrc")
            sources.add("Player" to directSrc)
        }

        document.select(".mobius option").forEach { opt ->
            val label = opt.text().trim()
            val b64 = opt.attr("value").trim()
            if (b64.isBlank()) return@forEach
            val decoded = base64Decode(b64)
            if (decoded.isBlank()) return@forEach
            val src = Jsoup.parse(decoded).selectFirst("iframe")?.attr("src").orEmpty()
                .ifBlank { Jsoup.parse(decoded).selectFirst("video source")?.attr("src").orEmpty() }
            if (src.isBlank()) return@forEach
            val finalUrl = when {
                src.startsWith("http") -> src
                src.startsWith("//") -> "https:$src"
                else -> return@forEach
            }
            Log.i(TAG, "[$label] → $finalUrl")
            sources.add(label to finalUrl)
        }

        if (sources.isEmpty()) {
            Log.w(TAG, "No sources found")
            return false
        }

        val seen = mutableSetOf<String>()
        sources.amap { (label, url) ->
            if (seen.add(url)) resolveVideo(url, data, subtitleCallback, callback)
        }

        Log.i(TAG, "=== loadLinks done ===")
        return true
    }

    companion object {
        private const val TAG = "Donghub"
        private val cfSolveMutex = Mutex()
    }
}
