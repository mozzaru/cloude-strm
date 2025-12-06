package com.Donghub

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Donghub : MainAPI() {
    override var mainUrl              = "https://Donghub.vip"
    override var name                 = "Donghub"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie,TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Rilisan Terbaru",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?status=hiatus&order=update" to "Series Drop/Hiatus",
        "anime/?type=movie&order=update" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page").document
        val home     = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title     = this.select("div.bsx > a").attr("title")
        val href      = fixUrl(this.select("div.bsx > a").attr("href"))
        val posterUrl = fixUrlNull(this.select("div.bsx > a img").attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }


    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..3) {
            val document = app.get("${mainUrl}/page/$i/?s=$query").document

            val results = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }

            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }

            if (results.isEmpty()) break
        }

        return searchResponse
    }

    @Suppress("SuspiciousIndentation")
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Ambil Title
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: ""
        
        // Ambil Poster (Coba dari div.ime, kalau gagal ambil dari meta tag)
        var poster = document.select("div.ime > img").attr("src")
        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim() ?: ""
        }
        
        // Ambil Deskripsi
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        
        // Cek Tipe (Movie atau Series)
        val type = document.selectFirst(".spe")?.text().toString()
        val isMovie = type.contains("Movie", true)

        return if (!isMovie) {
            val episodes = document.select("#chapterlist > ul > li, div.eplister > ul > li, ul.clstyle > li").mapNotNull { info ->
                val href = fixUrl(info.select("a").attr("href"))
                if (href.isEmpty()) return@mapNotNull null
                
                // Ambil nomor episode dan judul
                val numSpan = info.select(".epl-num, .chapternum, .be").text()
                val titleSpan = info.select(".epl-title").text()
                
                // Fallback nama episode
                val epName = if (titleSpan.isNotEmpty()) titleSpan else numSpan
                val epNum = numSpan.filter { it.isDigit() }.toIntOrNull()

                newEpisode(href) {
                    this.name = epName
                    this.episode = epNum
                    this.posterUrl = poster 
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val href = document.selectFirst("#epilist a, .eplister li a")?.attr("href") ?: url
            
            newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        document.select(".mobius option").forEach { server->
            val base64 = server.attr("value")
            val decoded=base64Decode(base64)
            val doc = Jsoup.parse(decoded)
            val href=doc.select("iframe").attr("src")
            val url = fixUrl(href)
            loadExtractor(url,subtitleCallback, callback)
        }
        return true
    }
}
