package com.Anichin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class NewPlayer : ExtractorApi() {
    override val name = "New Player"
    override val mainUrl = "https://short.icu"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // 1. Ikuti Redirect (short.icu -> abysscdn.com)
            // allowRedirects = true agar otomatis mendapatkan URL akhir
            val response = app.get(url, allowRedirects = true)
            val finalUrl = response.url
            val html = response.text

            // 2. Cek apakah sudah masuk ke abysscdn (New Player)
            if (finalUrl.contains("abysscdn.com") || finalUrl.contains("short.icu")) {
                
                // Mencari link MP4 langsung di dalam source HTML (berdasarkan source newplayer.txt baris 185-192)
                // Terkadang ada link storage.googleapis.com atau file MP4 lain yang bocor di script
                Regex("""(https?://.*?\.mp4)""").findAll(html).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = finalUrl
                            this.quality = Qualities.Unknown.value // Atau Qualities.P1080.value jika yakin
                        }
                    )
                }
                
                // TODO: Jika Regex MP4 tidak ketemu, seharusnya kita decrypt variable 'datas' 
                // yang ada di newplayer.txt. Tapi itu butuh logika dekripsi AES/Base64 yang kompleks.
                // Untuk sementara, Regex MP4 adalah solusi termudah.

            } else {
                // Jika redirect mengarah ke server lain (misal Dood/Mega)
                // Kita lempar kembali ke sistem loadExtractor global
                loadExtractor(finalUrl, referer, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
