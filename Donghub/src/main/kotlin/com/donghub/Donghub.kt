package com.donghub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Base64

class Donghub : MainAPI() {
    override var mainUrl = "https://donghub.vip"
    override var name = "Donghub"
    override val hasMainPage = true

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "id-ID,id;q=0.9",
        "Cache-Control" to "no-cache, no-store, must-revalidate",
        "Pragma" to "no-cache",
        "Expires" to "0",
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

    private val geoDmExtractor = CustomGeoDailymotion()
    private val dmExtractor    = CustomDailymotion()

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
            val document = app.get(mainUrl, headers = baseHeaders).document
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

        val document = app.get(url, headers = baseHeaders).document

        val items = if (request.data.isEmpty()) {
            val latestSection = document.select("div.bixbox").firstOrNull { box ->
                box.selectFirst("div.releases.latesthome") != null
            }
            latestSection?.select("article.bs")?.mapNotNull { it.toSearchResult() }
                ?: document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
        } else {
            document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
        }.distinctBy { it.url }

        val hasNext = document.selectFirst("div.hpage a.r") != null

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = hasNext
        )
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
               lower.contains("subtitle indonesia hanya di") ||
               lower.contains("mp4 mkv hardsub softsub") ||
               lower.contains("360p") || lower.contains("480p") ||
               lower.contains("720p") || lower.contains("terabox") ||
               lower.contains("mirrored") ||
               text.length < 30
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

        val typeLabel = selectFirst(".typez")?.text()?.lowercase().orEmpty()
        val type = if (href.contains("/movie/", ignoreCase = true) ||
            typeLabel.contains("movie")
        ) TvType.Movie else TvType.Anime

        val isEggLayout = selectFirst("div.egghead") != null

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
            when {
                "sub" in subLabel -> addSub(epNum)
                "dub" in subLabel -> addDub(epNum)
                else              -> addSub(epNum)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = baseHeaders).document
        return document.select("div.listupd article").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val firstDoc = app.get(url, headers = baseHeaders).document

        val allEpsLink = firstDoc.selectFirst("div.naveps.bignav .nvs.nvsc a")?.attr("href")

        val seriesUrl: String
        val document: org.jsoup.nodes.Document

        if (allEpsLink != null) {
            seriesUrl = fixUrl(allEpsLink)
            document  = app.get(seriesUrl, headers = baseHeaders).document
        } else {
            seriesUrl = url
            document  = firstDoc
        }

        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()

        var poster = document.selectFirst("div.ime > img")?.attr("src").orEmpty()
        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        }

        val synopsis = run {
            val synpEl = document.selectFirst("div.bixbox.synp div.entry-content")
            synpEl?.selectFirst("h1")?.remove()
            val synpText    = synpEl?.text()?.trim().orEmpty()
            val descText    = document.selectFirst("div.desc")?.text()?.trim().orEmpty()
            val mindescText = document.selectFirst("div.mindesc")?.text()?.trim().orEmpty()
            val metaDesc    = document.selectFirst("meta[property=og:description]")
                ?.attr("content")?.trim().orEmpty()

            when {
                synpText.isNotBlank()    && !isGenericTemplate(synpText)    -> synpText
                descText.isNotBlank()    && !isGenericTemplate(descText)    -> descText
                mindescText.isNotBlank() && !isGenericTemplate(mindescText) -> mindescText
                metaDesc.isNotBlank()    && metaDesc != title               -> metaDesc
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

        val episodeList = document.select("div.eplister ul li")
        val isSeries    = episodeList.isNotEmpty()
        val tvType      = if (isSeries) TvType.Anime else TvType.Movie
    
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
            val firstOption = document.selectFirst(".mobius option")
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(TAG, "=== loadLinks called ===")
        Log.d(TAG, "Data URL: $data")
    
        val document = app.get(data, headers = baseHeaders).document
        val options  = document.select(".mobius option")
        
        Log.i(TAG, "Server options found: ${options.size}")
        
        // Log all server options for debugging
        options.forEachIndexed { index, option ->
            val serverName = option.text().trim()
            val optionValue = option.attr("value")
            Log.d(TAG, "Server [$index]: name='$serverName', value_len=${optionValue.length}")
        }
    
        if (options.isEmpty()) {
            Log.w(TAG, "NO server options found on page!")
            // Try to find iframes directly
            val iframes = document.select("iframe")
            Log.d(TAG, "Direct iframes found: ${iframes.size}")
            iframes.forEachIndexed { index, iframe ->
                val src = iframe.attr("src")
                Log.d(TAG, "  Iframe [$index]: $src")
            }
        }
    
        var extractedCount = 0
        var skippedCount = 0
        
        options.forEach { server ->
            val base64 = server.attr("value").trim()
            if (base64.isBlank()) {
                Log.w(TAG, "Empty option value, skipping")
                skippedCount++
                return@forEach
            }
    
            try {
                Log.d(TAG, "Decoding server option...")
                val decoded  = base64Decode(base64)
                
                if (decoded.isBlank()) {
                    Log.w(TAG, "Decoded string is empty")
                    skippedCount++
                    return@forEach
                }
                
                Log.d(TAG, "Decoded length: ${decoded.length} chars")
                Log.d(TAG, "Decoded preview: ${decoded.take(150)}...")
                
                val iframe   = Jsoup.parse(decoded).selectFirst("iframe") ?: run {
                    Log.w(TAG, "No iframe found in decoded content")
                    // Try looking for other video sources
                    val videoSrc = Jsoup.parse(decoded).selectFirst("video source")?.attr("src")
                    if (videoSrc != null) {
                        Log.d(TAG, "Found video source: $videoSrc")
                        callback.invoke(newExtractorLink(
                            source = "DirectVideo",
                            name   = "Direct Video",
                            url    = videoSrc,
                            type   = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = data
                        })
                        extractedCount++
                    }
                    return@forEach
                }
    
                val iframeSrc = iframe.attr("src")
                    .ifBlank { iframe.attr("data-src") }.trim()
                if (iframeSrc.isBlank()) {
                    Log.w(TAG, "Iframe src is blank")
                    skippedCount++
                    return@forEach
                }
    
                val finalUrl = when {
                    iframeSrc.startsWith("http") -> iframeSrc
                    iframeSrc.startsWith("//")   -> "https:$iframeSrc"
                    else -> {
                        Log.w(TAG, "Invalid URL format: $iframeSrc")
                        return@forEach
                    }
                }
    
                val serverLabel = server.text().trim()
                Log.i(TAG, "========================================")
                Log.i(TAG, "Processing server: '$serverLabel'")
                Log.i(TAG, "URL: $finalUrl")
                Log.i(TAG, "========================================")
    
                when {
                    // ── Dailymotion: call custom extractor directly ──────
                    "geo.dailymotion.com" in finalUrl -> {
                        Log.d(TAG, "▶ CustomGeoDailymotion.getUrl")
                        geoDmExtractor.getUrl(finalUrl, finalUrl, subtitleCallback, callback)
                        extractedCount++
                    }
                    "dailymotion.com" in finalUrl || "dai.ly" in finalUrl -> {
                        Log.d(TAG, "▶ CustomDailymotion.getUrl")
                        dmExtractor.getUrl(finalUrl, finalUrl, subtitleCallback, callback)
                        extractedCount++
                    }
    
                    // ── DTube: loadExtractor with custom extractor ──────
                    "d.tube" in finalUrl || "d.tube" in finalUrl.lowercase() -> {
                        Log.d(TAG, "▶ loadExtractor DTube")
                        loadExtractor(finalUrl, finalUrl, subtitleCallback, callback)
                        extractedCount++
                    }
                    
                    // ── Mega: loadExtractor with custom extractor ──────
                    "mega.nz" in finalUrl.lowercase() || "mega.co.nz" in finalUrl.lowercase() -> {
                        Log.d(TAG, "▶ loadExtractor Mega")
                        loadExtractor(finalUrl, finalUrl, subtitleCallback, callback)
                        extractedCount++
                    }
    
                    // ── Other extractors ───────────────────────────────
                    else -> {
                        Log.d(TAG, "▶ loadExtractor fallback")
                        loadExtractor(finalUrl, finalUrl, subtitleCallback, callback)
                        extractedCount++
                    }
                }
    
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing server: ${e.message}")
                Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
                skippedCount++
            }
        }
        
        Log.i(TAG, "=== loadLinks completed ===")
        Log.i(TAG, "Extracted: $extractedCount, Skipped: $skippedCount")
        
        return true
    }
    
    companion object {
        private const val TAG = "Donghub"
    }
}
