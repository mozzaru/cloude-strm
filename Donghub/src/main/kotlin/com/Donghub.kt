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
        "" to "Rilisan Terbaru",
        "popular-today" to "Populer Hari Ini",
        "anime/?order=popular" to "Populer",
        "anime/?status=ongoing&order=update" to "Ongoing",
        "anime/?status=completed&order=update" to "Completed",
        "anime/?status=&type=movie&order=update" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            if (request.data == "popular-today") mainUrl else "$mainUrl/${request.data}"
        } else {
            if (request.data == "popular-today") return newHomePageResponse(
                list = HomePageList(name = request.name, list = emptyList(), isHorizontalImages = false),
                hasNext = false
            )
            val pagePath = "page/$page/"
            if (request.data.contains("?")) {
                val split = request.data.split("?")
                "$mainUrl/${split[0]}$pagePath?${split[1]}"
            } else {
                val dataPath = if (request.data.isEmpty()) "" else "${request.data.removeSuffix("/")}/"
                "$mainUrl/$dataPath$pagePath"
            }
        }
        val document = app.get(url, headers = baseHeaders).document
        val selector = when (request.data) {
            "popular-today" -> "div.listupd.popularslider article"
            "" -> "div.listupd.normal article, div.listupd:not(.popularslider) article"
            else -> "div.listupd article"
        }

        val items = document.select(selector).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        val hasNext = if (request.data == "popular-today") false else
            document.selectFirst("div.hpage a.r, div.pagination a.next, div.pagination a:contains(Next)") != null

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("div.bsx > a") ?: return null
        val rawTitle = (selectFirst(".eggtitle")?.text() ?: aTag.attr("title")).ifBlank { aTag.text() }.trim()
        val href = fixUrl(aTag.attr("href"))
        val img = aTag.selectFirst("img")

        val posterUrlRaw = img?.run {
            attr("data-src").ifBlank {
                attr("src")
            }.ifBlank {
                attr("data-lazy-src")
            }
        }.orEmpty()

        val posterUrlFixed = if (posterUrlRaw.startsWith("//")) {
            "https:$posterUrlRaw"
        } else {
            posterUrlRaw
        }

        val posterUrl = fixUrlNull(posterUrlFixed)

        val typeLabel = selectFirst(".typez, .eggtype")?.text()?.lowercase().orEmpty()
        val type = if (href.contains("/movie/", ignoreCase = true) ||
            typeLabel.contains("movie")
        ) TvType.Movie else TvType.Anime
        val statusLabel = this.selectFirst("div.bt span")?.text()?.lowercase().orEmpty()

        val titleWithStatus = if ("complete" in statusLabel) {
            "$rawTitle (Completed)"
        } else {
            rawTitle
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(titleWithStatus, href, type) {
                this.posterUrl = posterUrl
            }
        } else {
            val epText = selectFirst(".eggepisode")?.text() ?: selectFirst(".epx")?.text()
            val epNum = epText?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
            newAnimeSearchResponse(titleWithStatus, href, type) {
                this.posterUrl = posterUrl
                addSub(epNum)
            }
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
                val epNum = li.selectFirst("div.epl-num")?.text()?.trim()
                val epTitle = li.selectFirst("div.epl-title")?.text()?.trim()

                newEpisode(epHref) {
                    this.name = epTitle ?: "Episode $epNum"
                    this.episode = epNum?.toIntOrNull()
                    this.posterUrl = poster  // fallback poster anime, donghub tidak ada thumbnail per episode
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
