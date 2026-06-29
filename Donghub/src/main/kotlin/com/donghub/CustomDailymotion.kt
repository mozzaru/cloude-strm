package com.donghub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * CustomDailymotion - Adaptive HLS + per-variant tracks
 * ======================================================
 * Strategy:
 *   1. Kirim master .m3u8 sebagai satu link "Auto" → player bisa track-switch resolusi
 *   2. Kirim juga tiap variant sebagai link terpisah (fallback manual)
 *   3. Subtitles tetap dikirim via subtitleCallback
 */

private data class DmMetaData(
    val qualities: Map<String, List<DmQuality>>?,
    val subtitles: DmSubtitlesWrapper?,
    val message: String? = null
)

private data class DmQuality(
    val type: String?,
    val url: String?
)

private data class DmSubtitlesWrapper(
    val enable: Boolean?,
    val data: Map<String, DmSubtitleData>?
)

private data class DmSubtitleData(
    val label: String,
    val urls: List<String>
)

private data class HlsVariant(
    val bandwidth: Int,
    val resolution: String?,
    val url: String
)

// ── geo.dailymotion.com handler ───────────────────────────────

class CustomGeoDailymotion : CustomDailymotion() {
    override val name    = "Dailymotion"
    override val mainUrl = "https://geo.dailymotion.com"
}

// ── www.dailymotion.com handler ───────────────────────────────

open class CustomDailymotion : ExtractorApi() {
    override val name            = "Dailymotion"
    override val mainUrl         = "https://www.dailymotion.com"
    override val requiresReferer = false

    private val baseUrl      = "https://www.dailymotion.com"
    private val videoIdRegex = Regex("^[kx][a-zA-Z0-9]+$")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "getUrl: $url")

        val (videoId, correctReferer) = parseVideoInfo(url) ?: run {
            Log.e(TAG, "Failed to parse video info from: $url")
            return
        }

        Log.i(TAG, "videoId=$videoId  referer=$correctReferer")

        val metaDataUrl = "$baseUrl/player/metadata/video/$videoId"
        val response = try {
            app.get(
                metaDataUrl,
                referer = correctReferer,
                headers = mapOf(
                    "Origin"          to baseUrl,
                    "Accept"          to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9,id;q=0.8"
                )
            ).text
        } catch (e: Exception) {
            Log.e(TAG, "Metadata request failed: ${e.message}")
            return
        }

        val meta = try {
            parseJson<DmMetaData>(response)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse failed: ${e.message}")
            return
        }

        meta.message?.let {
            Log.e(TAG, "DM error: $it")
            return
        }

        val qualities = meta.qualities
        if (qualities.isNullOrEmpty()) {
            Log.e(TAG, "No qualities in metadata")
            return
        }

        Log.i(TAG, "Quality keys: ${qualities.keys.joinToString()}")

        // ── 1. Cari master m3u8 dari key "auto" ──────────────────
        val autoEntry = (qualities["auto"] ?: qualities.values.firstOrNull())
            ?.firstOrNull { it.url?.contains(".m3u8") == true }

        val masterM3u8Url = autoEntry?.url

        if (masterM3u8Url != null) {
            Log.d(TAG, "Master m3u8: ${masterM3u8Url.take(80)}...")

            // ── 2. Kirim master URL sebagai satu link "Auto" ──────
            //    Gunakan name eksplisit "Auto" agar CloudStream tidak
            //    menambahkan label quality di belakangnya (menghindari
            //    label ganda seperti "Dailymotion Auto Auto").
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name   = "$name Auto",
                    url    = masterM3u8Url,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = correctReferer
                }
            )
            Log.i(TAG, "Sent master m3u8 as adaptive track")

            // ── 3. Parse manifest → kirim juga per-variant ────────
            //    Agar user bisa memilih resolusi spesifik dari daftar sumber.
            val variants = parseHlsManifest(masterM3u8Url, correctReferer)

            if (variants.isNotEmpty()) {
                Log.i(TAG, "Sending ${variants.size} individual variant tracks")
                // Deduplicate: keep highest-bandwidth variant per quality level
                // to avoid duplicate "Dailymotion 1080p 1080p" entries
                val bestVariants = variants
                    .groupBy { resolutionToQuality(it.resolution) }
                    .mapValues { (_, list) -> list.maxByOrNull { it.bandwidth }!! }
                    .values.sortedByDescending { it.bandwidth }

                for (variant in bestVariants) {
                    val qualityInt   = resolutionToQuality(variant.resolution)
                    val qualityLabel = qualityIntToLabel(qualityInt)
                    Log.d(TAG, "  Variant: ${variant.bandwidth}bps res=${variant.resolution} → $qualityLabel")

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            // Eksplisit include qualityLabel di name supaya
                            // CloudStream tidak dobel-append quality label lagi
                            name   = "$name",
                            url    = variant.url,
                            type   = ExtractorLinkType.M3U8
                        ) {
                            this.quality = qualityInt
                            this.referer = correctReferer
                        }
                    )
                }
            } else {
                Log.w(TAG, "No variants parsed from manifest (will rely on Auto track only)")
            }
        } else {
            Log.w(TAG, "No m3u8 found in 'auto' quality key")
        }

        // ── 4. Direct MP4 (non-auto keys) ─────────────────────────
        for ((key, entries) in qualities) {
            if (key == "auto") continue
            for (entry in entries) {
                val entryUrl = entry.url ?: continue
                if (!entryUrl.contains(".mp4")) continue

                val qualityInt   = qualityKeyToEnum(key)
                val qualityLabel = qualityIntToLabel(qualityInt)
                Log.d(TAG, "  Direct MP4 $key → ${entryUrl.take(60)}...")

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = name,
                        url    = entryUrl,
                        type   = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = qualityInt
                        this.referer = correctReferer
                    }
                )
            }
        }

        // ── 5. Subtitles ──────────────────────────────────────────
        meta.subtitles?.data?.forEach { (lang, subData) ->
            subData.urls.forEach { subUrl ->
                Log.d(TAG, "Subtitle: $lang → $subUrl")
                subtitleCallback(newSubtitleFile(subData.label, subUrl))
            }
        }
    }

    // ── HLS Manifest Parser ───────────────────────────────────────

    private suspend fun parseHlsManifest(manifestUrl: String, referer: String): List<HlsVariant> {
        val variants = mutableListOf<HlsVariant>()
        return try {
            val text = app.get(
                manifestUrl,
                headers = mapOf(
                    "Referer" to referer,
                    "Origin"  to baseUrl
                )
            ).text

            Log.d(TAG, "Manifest length: ${text.length}")

            // Primary parse: regex across full text
            val streamInfRegex = Regex("""#EXT-X-STREAM-INF:([^\n]+)\n([^\n]+)""")
            streamInfRegex.findAll(text).forEach { match ->
                val attrs      = match.groupValues[1]
                val variantUrl = match.groupValues[2].trim()
                val bandwidth  = Regex("""BANDWIDTH=(\d+)""").find(attrs)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val resolution = Regex("""RESOLUTION=(\d+x\d+)""").find(attrs)
                    ?.groupValues?.get(1)
                if (variantUrl.isNotBlank()) variants.add(HlsVariant(bandwidth, resolution, variantUrl))
            }

            // Fallback: line-by-line
            if (variants.isEmpty()) {
                Log.d(TAG, "Regex parse empty, trying line-by-line")
                var lastAttrs = ""
                for (line in text.lines()) {
                    when {
                        line.startsWith("#EXT-X-STREAM-INF:") -> lastAttrs = line.removePrefix("#EXT-X-STREAM-INF:")
                        !line.startsWith("#") && line.isNotBlank() && lastAttrs.isNotBlank() -> {
                            val bandwidth  = Regex("""BANDWIDTH=(\d+)""").find(lastAttrs)
                                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val resolution = Regex("""RESOLUTION=(\d+x\d+)""").find(lastAttrs)
                                ?.groupValues?.get(1)
                            variants.add(HlsVariant(bandwidth, resolution, line.trim()))
                            lastAttrs = ""
                        }
                    }
                }
            }

            variants.sortByDescending { it.bandwidth }
            Log.d(TAG, "Parsed ${variants.size} variants")
            variants
        } catch (e: Exception) {
            Log.e(TAG, "parseHlsManifest failed: ${e.message}")
            variants
        }
    }

    // ── Quality helpers ───────────────────────────────────────────

    private fun resolutionToQuality(resolution: String?): Int {
        val height = resolution?.substringAfter("x")?.substringBefore("?")
            ?.toIntOrNull() ?: return Qualities.Unknown.value
        return when {
            height >= 2160 -> Qualities.P2160.value
            height >= 1440 -> Qualities.P1440.value
            height >= 1080 -> Qualities.P1080.value
            height >= 720  -> Qualities.P720.value
            height >= 480  -> Qualities.P480.value
            height >= 360  -> Qualities.P360.value
            height >= 240  -> Qualities.P240.value
            else           -> Qualities.P144.value
        }
    }

    private fun qualityKeyToEnum(key: String): Int = when (key) {
        "1080" -> Qualities.P1080.value
        "720"  -> Qualities.P720.value
        "480"  -> Qualities.P480.value
        "380"  -> Qualities.P360.value
        "240"  -> Qualities.P240.value
        "144"  -> Qualities.P144.value
        else   -> Qualities.Unknown.value
    }

    private fun qualityIntToLabel(qualityInt: Int): String = when (qualityInt) {
        Qualities.P2160.value -> "2160p"
        Qualities.P1440.value -> "1440p"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value  -> "720p"
        Qualities.P480.value  -> "480p"
        Qualities.P360.value  -> "360p"
        Qualities.P240.value  -> "240p"
        Qualities.P144.value  -> "144p"
        else                  -> "Auto"
    }

    // ── URL parsing ───────────────────────────────────────────────

    private fun parseVideoInfo(url: String): Pair<String, String>? {
        val s = url.trim()

        if (s.matches(videoIdRegex)) return Pair(s, "$baseUrl/embed/video/$s")

        if ("geo.dailymotion.com" in s) {
            val vid = Regex("""[?&]video=([kx][a-zA-Z0-9]+)""").find(s)?.groupValues?.get(1)
                ?: s.substringAfterLast("/").replace(".html", "").takeIf { it.matches(videoIdRegex) }
                ?: return null.also { Log.w(TAG, "Cannot extract video ID from geo URL") }
            return Pair(vid, s)
        }

        if ("dailymotion.com" in s) {
            val vid = Regex("""/video/([kx][a-zA-Z0-9]+)""").find(s)?.groupValues?.get(1)
                ?: return null.also { Log.w(TAG, "Cannot extract video ID from dailymotion URL") }
            return Pair(vid, "$baseUrl/embed/video/$vid")
        }

        Log.w(TAG, "URL format not recognized: $s")
        return null
    }

    companion object {
        private const val TAG = "CustomDailymotion"
    }
}
