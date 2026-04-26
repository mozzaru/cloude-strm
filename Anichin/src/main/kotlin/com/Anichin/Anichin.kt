package com.Anichin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Anichin : MainAPI() {
    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Rilisan Terbaru",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?status=hiatus&order=update" to "Series Drop/Hiatus",
        "anime/?type=movie&order=update" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            val (path, query) = if (request.data.contains("?")) {
                request.data.split("?", limit = 2)
            } else {
                listOf(request.data, "")
            }
            val cleanPath = path.trimEnd('/')
            if (query.isNotEmpty()) {
                "$mainUrl/$cleanPath/page/$page/?$query"
            } else {
                "$mainUrl/$cleanPath/page/$page/"
            }
        }
        val document = app.get(url).document
        val items = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }

        val hasNext = document.selectFirst("a.next, a.page-numbers.next") != null

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("div.bsx > a") ?: return null
        val rawTitle = aTag.attr("title").ifBlank { aTag.text() }
        val href = fixUrl(aTag.attr("href"))
        val img = aTag.selectFirst("img")

        val posterUrlRaw = img?.run {
            attr("src").ifBlank { attr("data-src") }.ifBlank { attr("data-lazy-src") }
        }.orEmpty()

        val posterUrlFixed = if (posterUrlRaw.startsWith("//")) "https:$posterUrlRaw" else posterUrlRaw
        val posterUrl = fixUrlNull(posterUrlFixed)

        // Anichin: href adalah episode page, ambil series title dari div.tt
        val seriesTitle = selectFirst("div.tt")?.ownText()?.trim()
            ?.ifBlank { rawTitle } ?: rawTitle

        val type = if (href.contains("movie", ignoreCase = true) ||
            selectFirst(".typez")?.text()?.contains("movie", ignoreCase = true) == true
        ) TvType.Movie else TvType.Anime

        return newMovieSearchResponse(seriesTitle, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.listupd > article").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        var poster = document.selectFirst("div.ime > img")?.attr("src").orEmpty()
        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        }
        val description = document.selectFirst("div.entry-content")?.text()?.trim()

        // Anichin: episode list ada di halaman episode, bukan series
        // Ambil dari div.eplister ul li
        val episodeList = document.select("div.eplister ul li")

        val isSeries = episodeList.isNotEmpty()
        val tvType = if (isSeries) TvType.Anime else TvType.Movie

        val episodes = if (isSeries) {
            episodeList.mapNotNull { li ->
                val a = li.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrl(a.attr("href"))
                val epTitle = li.selectFirst("div.epl-title")?.text()?.trim()
                val epNumText = li.selectFirst("div.epl-num")?.text()?.trim()
                val epNum = epNumText?.toIntOrNull()
                // Thumbnail per episode tersedia!
                val epPoster = li.selectFirst("div.epl-image img")?.run {
                    attr("src").ifBlank { attr("data-src") }
                }.orEmpty()

                newEpisode(epHref) {
                    this.name = epTitle ?: "Episode $epNum"
                    this.episode = epNum
                    this.posterUrl = epPoster.ifBlank { poster }
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
        val document = app.get(data).document

        document.select(".mobius option").forEach { server ->
            val base64 = server.attr("value").trim()
            if (base64.isBlank()) return@forEach

            try {
                val decoded = base64Decode(base64)
                val iframe = Jsoup.parse(decoded).selectFirst("iframe")
                val iframeSrc = iframe?.attr("src")?.ifBlank { iframe.attr("data-src") }?.trim()
                if (!iframeSrc.isNullOrBlank()) {
                    val finalUrl = if (iframeSrc.startsWith("http")) iframeSrc else "https:$iframeSrc"
                    println("🎯 [Anichin] Trying to extract: $finalUrl")

                    loadExtractor(finalUrl, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                println("❌ [Anichin] Error decoding Base64 or extracting: ${e.message}")
            }
        }

        return true
    }
}
