package com.Donghub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class TurboVid : ExtractorApi() {
    override val name = "TurboVid"
    override val mainUrl = "https://turbovidhls.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // PENTING: Berdasarkan Baris 190 di HTML, player mengecek document.referrer.
        // Kita wajib kirim referer Donghub.
        val finalReferer = referer ?: "https://donghub.vip/"
        val headers = mapOf(
            "Referer" to finalReferer,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        )

        Log.d("TurboVid", "Mengambil URL: $url dengan Referer: $finalReferer")

        try {
            val response = app.get(url, headers = headers)
            val html = response.text

            // TARGET 1: Baris 189 di respon_turbovid1.txt
            // var urlPlay = 'https://...m3u8';
            var m3u8Url = Regex("""var\s+urlPlay\s*=\s*['"](.*?)['"]""").find(html)?.groupValues?.get(1)

            // TARGET 2: Baris 181 di respon_turbovid1.txt (Cadangan)
            // data-hash="https://...m3u8"
            if (m3u8Url == null) {
                m3u8Url = Regex("""data-hash\s*=\s*['"](.*?)['"]""").find(html)?.groupValues?.get(1)
            }

            Log.d("TurboVid", "Hasil M3U8: $m3u8Url")

            if (m3u8Url != null && m3u8Url.contains(".m3u8")) {
                // Generate link untuk semua kualitas (360p, 720p, 1080p)
                M3u8Helper.generateM3u8(
                    name,
                    m3u8Url,
                    url, // Referer file m3u8 biasanya halaman embed itu sendiri
                    headers = headers
                ).forEach(callback)
            } else {
                Log.e("TurboVid", "Link M3U8 tidak ditemukan di HTML")
            }

        } catch (e: Exception) {
            Log.e("TurboVid", "Error: ${e.message}")
            e.printStackTrace()
        }
    }
}
