package com.donghub

import com.lagradost.api.Log
import com.google.gson.Gson
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

open class DonghubDailymotion : ExtractorApi() {
    override val name            = "Dailymotion"
    override val mainUrl         = "https://www.dailymotion.com"
    override val requiresReferer = false

    private val baseUrl      = "https://www.dailymotion.com"
    private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

    companion object {
        private const val TAG = "DonghubDailymotion"

        // Hanya dipakai untuk label nama, bukan memecah stream
        private val heightToQuality = mapOf(
            2160 to Qualities.P2160.value,
            1080 to Qualities.P1080.value,
            720  to Qualities.P720.value,
            480  to Qualities.P480.value,
            360  to Qualities.P360.value,
            240  to Qualities.P240.value,
            144  to Qualities.P144.value,
        )
    }

    private val commonHeaders = mapOf(
        "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                             "AppleWebKit/537.36 (KHTML, like Gecko) " +
                             "Chrome/124.0.0.0 Safari/537.36",
        "Accept"          to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.i(TAG, "getUrl → $url")

        val embedUrl = getEmbedUrl(url) ?: run {
            Log.e(TAG, "getEmbedUrl gagal: $url"); return
        }
        val id = getVideoId(embedUrl) ?: run {
            Log.e(TAG, "getVideoId gagal: $embedUrl"); return
        }
        Log.i(TAG, "video id → $id")

        val metaUrl  = "$baseUrl/player/metadata/video/$id"
        val response = app.get(
            metaUrl,
            headers = commonHeaders + mapOf(
                "Referer" to embedUrl,
                "Origin"  to baseUrl,
            ),
            referer = embedUrl
        )

        Log.i(TAG, "metadata HTTP ${response.code}")
        if (!response.isSuccessful) {
            Log.e(TAG, "metadata gagal: ${response.text.take(200)}"); return
        }

        val meta = try {
            Gson().fromJson(response.text, MetaData::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}"); return
        }

        val qualities = meta.qualities
        if (qualities.isNullOrEmpty()) {
            Log.e(TAG, "qualities kosong"); return
        }

        Log.i(TAG, "quality keys: ${qualities.keys}")

        // ─── STRATEGI: selalu cari master playlist ───────────────────────
        // "auto" = master HLS dengan semua variant + audio group (BENAR)
        // Jangan pisah-pisah per resolusi → audio hilang, lag
        // ─────────────────────────────────────────────────────────────────

        val streamHeaders = commonHeaders + mapOf(
            "Referer" to embedUrl,
            "Origin"  to baseUrl,
        )

        val masterUrl = findMasterUrl(qualities)

        if (masterUrl != null) {
            Log.i(TAG, "master m3u8 → ${masterUrl.take(80)}...")

            // Fetch master untuk detect kualitas tertinggi (label saja)
            val maxHeight = detectMaxHeight(masterUrl, streamHeaders)
            val quality   = heightToQuality.entries
                .minByOrNull { (h, _) -> Math.abs(h - maxHeight) }
                ?.value ?: Qualities.Unknown.value
            val label = if (maxHeight > 0) "up to ${maxHeight}p" else "Auto"

            // Kirim SATU link master → ExoPlayer handle quality picker + audio
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name   = "$name $label",
                    url    = masterUrl,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.quality = quality
                    this.referer = embedUrl
                    this.headers = streamHeaders
                }
            )
            Log.i(TAG, "✅ 1 master stream dikirim (quality: $label)")
        } else {
            Log.e(TAG, "tidak ada .m3u8 ditemukan di semua quality keys")
        }

        // Subtitle
        meta.subtitles?.data?.forEach { (_, sub) ->
            sub.urls.forEach { subUrl ->
                subtitleCallback(newSubtitleFile(sub.label, subUrl))
            }
        }
    }

    /**
     * Cari master playlist URL dari qualities map.
     * Prioritas: "auto" → key lain dengan .m3u8
     */
    private fun findMasterUrl(qualities: Map<String, List<Quality>>): String? {
        // Prioritas utama: "auto" = confirmed master playlist
        qualities["auto"]?.forEach { q ->
            val u = q.url ?: return@forEach
            if (u.contains(".m3u8")) return u
        }
        // Fallback: key lain (misal Dailymotion suatu saat ubah struktur)
        for ((key, list) in qualities) {
            if (key == "auto") continue
            list.forEach { q ->
                val u = q.url ?: return@forEach
                // Pastikan ini master, bukan segment — cek tidak ada _720 / _1080 pattern
                if (u.contains(".m3u8") && !u.contains("_${key}")) return u
            }
        }
        return null
    }

    /**
     * Fetch master m3u8, parse RESOLUTION untuk tahu kualitas tertinggi.
     * Hanya untuk label — tidak memisah stream.
     */
    private suspend fun detectMaxHeight(
        masterUrl: String,
        headers: Map<String, String>
    ): Int {
        return try {
            val text = app.get(masterUrl, headers = headers).text
            Regex("""RESOLUTION=\d+x(\d+)""")
                .findAll(text)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .maxOrNull() ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "detectMaxHeight error: ${e.message}")
            0
        }
    }

    private fun getEmbedUrl(url: String): String? {
        if (url.contains("/embed/") || url.contains("/video/")) return url
        if (url.contains("geo.dailymotion.com")) {
            val videoId = url.substringAfter("video=")
            return "$baseUrl/embed/video/$videoId"
        }
        return null
    }

    private fun getVideoId(url: String): String? {
        val path = URI(url).path
        val id   = path.substringAfter("/video/")
        return if (id.matches(videoIdRegex)) id else null
    }

    data class MetaData(
        val qualities : Map<String, List<Quality>>?,
        val subtitles : SubtitlesWrapper?
    )
    data class Quality(val type: String?, val url: String?)
    data class SubtitlesWrapper(val enable: Boolean, val data: Map<String, SubtitleData>?)
    data class SubtitleData(val label: String, val urls: List<String>)
}
