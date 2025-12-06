package com.Anichin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.Anichin.JsUnpacker 

class RPMVid : ExtractorApi() {
    override val name = "RPMShare"
    override val mainUrl = "https://rpmvid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Gunakan referer yang dikirim atau fallback ke host utama
        val headers = mapOf("Referer" to (referer ?: "https://anichin.my.id/"))

        try {
            val response = app.get(url, headers = headers)
            var html = response.text

            // 1. Cek JS Unpack (Wajib untuk RPMVid/PlayerJS)
            if (html.contains("eval(function(p,a,c,k,e,d)")) {
                html = JsUnpacker.unpack(html) ?: html
            }

            // 2. Regex Universal (Menangkap .m3u8 dan .mp4)
            // Menangani variasi kutip satu (') atau dua (")
            val regex = Regex("""(https?:\\?/\\?/[^"']+\.(?:m3u8|mp4))""")
            
            regex.findAll(html).forEach { match ->
                // PENTING: Bersihkan URL dari escape character (contoh: https:\/\/ -> https://)
                val rawUrl = match.groupValues[1]
                val cleanUrl = rawUrl.replace("\\/", "/")
                
                if (cleanUrl.contains(".m3u8")) {
                    // M3u8Helper otomatis membuat multi-quality (360p, 720p, dll)
                    M3u8Helper.generateM3u8(
                        name,
                        cleanUrl,
                        url, // Referer m3u8 biasanya url embed itu sendiri
                        headers = headers
                    ).forEach(callback)
                } else {
                    // Untuk file MP4 langsung
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = cleanUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
