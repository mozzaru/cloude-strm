package com.yunshanid

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class GdriveExtractor : ExtractorApi() {
    override var name = "Google Drive"
    override var mainUrl = "https://drive.google.com"
    override val requiresReferer = false

    private val fileIdRegex = listOf(
        Regex("/file/d/([a-zA-Z0-9_-]+)"),
        Regex("id=([a-zA-Z0-9_-]+)"),
        Regex("/open\\?id=([a-zA-Z0-9_-]+)")
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("GdriveExtractor", "Processing URL: $url")

        if (!url.contains("drive.google.com")) {
            Log.d("GdriveExtractor", "URL bukan Google Drive, skip")
            return
        }

        // Ekstrak file ID
        val fileId = fileIdRegex.firstNotNullOfOrNull { regex ->
            regex.find(url)?.groupValues?.get(1)
        }

        if (fileId == null) {
            Log.e("GdriveExtractor", "Gagal ekstrak file ID dari: $url")
            return
        }

        Log.d("GdriveExtractor", "File ID: $fileId")

        tryDirectDownload(fileId, callback)

        tryViewerStream(fileId, referer, callback)
    }

    private suspend fun tryDirectDownload(
        fileId: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val downloadUrl = "https://drive.google.com/uc?id=$fileId&export=download&confirm=t"

        Log.d("GdriveExtractor", "Mencoba direct download: $downloadUrl")

        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://drive.google.com/"
            )

            val response = app.get(
                downloadUrl,
                headers = headers,
                allowRedirects = false
            )

            Log.d("GdriveExtractor", "Direct download status: ${response.code}")

            val location = response.headers["location"]
            if (location != null && (location.contains("videoplayback") || location.contains("drive.google.com"))) {
                Log.d("GdriveExtractor", "Redirect ke: $location")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Direct",
                        url = location,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                        this.referer = "https://drive.google.com/"
                    }
                )
                return
            }

            val body = response.text
            if (body.contains("confirm=")) {
                val confirm = Regex("confirm=([^&\"\\s]+)").find(body)?.groupValues?.get(1)
                if (confirm != null) {
                    val confirmedUrl = "https://drive.google.com/uc?id=$fileId&export=download&confirm=$confirm"
                    Log.d("GdriveExtractor", "Menggunakan confirm token: $confirmedUrl")
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name Confirmed",
                            url = confirmedUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                            this.referer = "https://drive.google.com/"
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("GdriveExtractor", "Error direct download: ${e.message}")
        }
    }

    private suspend fun tryViewerStream(
        fileId: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val previewUrl = "https://drive.google.com/file/d/$fileId/preview"

        Log.d("GdriveExtractor", "Mencoba viewer stream dari: $previewUrl")

        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
                "Referer" to (referer ?: "https://drive.google.com/"),
                "Accept-Language" to "id-ID,id;q=0.9"
            )

            val response = app.get(previewUrl, headers = headers)
            val html = response.text

            Log.d("GdriveExtractor", "Viewer response length: ${html.length}")

            val streamPatterns = listOf(
                Regex("(https://rr[^\"\\s]+videoplayback[^\"\\s]+)"),
                Regex("\"(https://[^\"]+\\.c\\.drive\\.google\\.com/videoplayback[^\"]+)\""),
                Regex("(https://drive\\.usercontent\\.google\\.com/uc\\?id=[^\"\\s&]+[^\"\\s]*)")
            )

            var found = false
            for (pattern in streamPatterns) {
                val matches = pattern.findAll(html)
                for (match in matches) {
                    val streamUrl = match.groupValues[1]
                        .replace("\\u003d", "=")
                        .replace("\\u0026", "&")
                        .replace("\\u003c", "<")
                        .replace("\\u003e", ">")

                    if (streamUrl.isBlank()) continue

                    val itag = Regex("[?&]itag=(\\d+)").find(streamUrl)?.groupValues?.get(1)?.toIntOrNull()
                    val quality = when (itag) {
                        137, 248 -> Qualities.P1080.value
                        136, 247 -> Qualities.P720.value
                        135, 244 -> Qualities.P480.value
                        134, 243 -> Qualities.P360.value
                        133, 242 -> Qualities.P240.value
                        160, 278 -> Qualities.P144.value
                        else -> Qualities.Unknown.value
                    }

                    // Skip audio-only
                    val mime = Regex("[?&]mime=([^&]+)").find(streamUrl)?.groupValues?.get(1)
                    if (mime?.contains("audio") == true) {
                        Log.d("GdriveExtractor", "Skip audio-only stream: $mime")
                        continue
                    }

                    val qualityName = when (quality) {
                        Qualities.P1080.value -> "1080p"
                        Qualities.P720.value -> "720p"
                        Qualities.P480.value -> "480p"
                        Qualities.P360.value -> "360p"
                        Qualities.P240.value -> "240p"
                        Qualities.P144.value -> "144p"
                        else -> "Auto"
                    }

                    Log.d("GdriveExtractor", "Stream ditemukan ($qualityName, itag=$itag): ${streamUrl.take(80)}...")

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name $qualityName",
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36",
                                "Referer" to "https://drive.google.com/"
                            )
                            this.referer = "https://drive.google.com/"
                        }
                    )
                    found = true
                }
                if (found) break
            }

            if (!found) {
                Log.w("GdriveExtractor", "Tidak ada stream URL ditemukan di viewer. Coba usercontent fallback.")

                // Fallback ke usercontent
                val usercontent = "https://drive.usercontent.google.com/uc?id=$fileId&export=download&confirm=t"
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Fallback",
                        url = usercontent,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = "https://drive.google.com/"
                    }
                )
            }

        } catch (e: Exception) {
            Log.e("GdriveExtractor", "Error viewer stream: ${e.message}")
        }
    }
}
