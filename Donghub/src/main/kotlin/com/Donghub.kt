package com.donghub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Donghub : MainAPI() {
    override var mainUrl = "https://donghub.vip"
    override var name = "Donghub"
    override val hasMainPage = true

    private val baseHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Sec-Ch-Ua" to "\"Not-A.Chromium\";v=\"124\", \"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Sec-Fetch-User" to "?1"
    )
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Rilisan Terbaru",
        "popular-today" to "Populer Hari Ini",
        "anime/?order=popular" to "Populer",
        "anime/?status=ongoing&sub=&order=" to "Ongoing",
        "anime/?status=completed&type=" to "Completed",
        "anime/?status=&type=movie&order=" to "Movie"
    )

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

        val url = if (page == 1) "$mainUrl/${request.data}"
        else "$mainUrl/${request.data}&page=$page"

        val document = app.get(url, headers = baseHeaders).document
        val items = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        val hasNext = document.selectFirst("div.hpage a.r") != null

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = hasNext
        )
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

        val epxText = selectFirst("span.epx")?.text().orEmpty()
        val statusLabel = selectFirst("div.bt span")?.text()?.lowercase().orEmpty()
        val titleWithStatus = if ("tamat" in epxText.lowercase() || "complete" in statusLabel) {
            "$rawTitle (Completed)"
        } else rawTitle

        val epNum = epxText.replace(Regex("[^0-9]"), "").toIntOrNull()

        return newAnimeSearchResponse(titleWithStatus, href, type) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = baseHeaders).document
        return document.select("div.listupd article").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val initialDocument = app.get(url, headers = baseHeaders).document

        // Check if it's an episode page
        val seriesLink = initialDocument.selectFirst("div.breadcrumb a[href*=\"/anime/\"], div.breadcrumb a[href*=\"/series/\"], span.all-episodes a")
            ?.attr("href")
            ?: initialDocument.selectFirst("div.nvs.nvsc a")?.attr("href") // "All Episodes" button in some themes

        if (!seriesLink.isNullOrBlank() && url.contains("-episode-")) {
            return load(fixUrl(seriesLink))
        }

        val document = initialDocument
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        var poster = document.selectFirst("div.ime > img")?.attr("src").orEmpty()
        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        }
        val description = document.selectFirst("div.entry-content")?.text()?.trim()

        // Selector fix sesuai HTML aktual Donghub
        val episodeList = document.select("div.eplister ul li")

        val isSeries = episodeList.isNotEmpty()
        val tvType = if (isSeries) TvType.Anime else TvType.Movie

        val episodes = if (isSeries) {
            episodeList.mapNotNull { li ->
                val a = li.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrl(a.attr("href"))
                val epNumText = li.selectFirst("div.epl-num")?.text()?.trim().orEmpty()
                val epNum = epNumText.replace(Regex("[^0-9]"), "").toIntOrNull()
                val epTitle = li.selectFirst("div.epl-title")?.text()?.trim()
                
                newEpisode(epHref) {
                    this.name = epTitle ?: "Episode $epNum"
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }.reversed()
        } else {
            val firstOption = document.selectFirst(".mobius option")
            val base64 = firstOption?.attr("value")?.trim()
            var playUrl: String? = null

            if (!base64.isNullOrBlank()) {
                try {
                    val decoded = base64Decode(base64)
                    val iframe = Jsoup.parse(decoded).selectFirst("iframe")
                    val rawSrc = iframe?.attr("src")
                    if (!rawSrc.isNullOrBlank()) {
                        playUrl = if (rawSrc.startsWith("http")) rawSrc else "https:$rawSrc"
                    }
                } catch (_: Exception) {}
            }

            if (playUrl == null) playUrl = url

            listOf(newEpisode(playUrl) {
                name = "Movie"
                posterUrl = poster
            })
        }

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = baseHeaders).document

        document.select(".mobius option").forEach { server ->
            val base64 = server.attr("value").trim()
            if (base64.isBlank()) return@forEach

            try {
                val decoded = base64Decode(base64)
                val iframe = Jsoup.parse(decoded).selectFirst("iframe")
                val iframeSrc = iframe?.attr("src")?.ifBlank { iframe.attr("data-src") }?.trim()
                if (!iframeSrc.isNullOrBlank()) {
                    val finalUrl = if (iframeSrc.startsWith("http")) iframeSrc else "https:$iframeSrc"
                    println("🎯 [Donghub] Trying to extract: $finalUrl")

                    loadExtractor(finalUrl, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                println("❌ [Donghub] Error decoding Base64 or extracting: ${e.message}")
            }
        }

        return true
    }
}
