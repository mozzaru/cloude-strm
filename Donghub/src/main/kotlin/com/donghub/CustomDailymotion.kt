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
 * CustomDailymotion - Fixed Version with HLS Manifest Parsing
 * =========================================
 * Problem: Dailymotion metadata API only returns "auto" quality key
 * Solution: Fetch and parse the .m3u8 manifest to extract quality variants
 * 
 * Improvements:
 * - Parse HLS manifest for quality variants
 * - Extract resolution from manifest bandwidth info
 * - Enhanced logging for debugging
 * - Fallback to original behavior if manifest parsing fails
 */

private data class DmMetaData(
    val qualities: Map<String, List<DmQuality>>?,
    val subtitles: DmSubtitlesWrapper?,
    val message: String? = null,
    val streamFormats: Map<String, String>? = null  // Added for additional info
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

// HLS variant stream data class
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

    private val baseUrl         = "https://www.dailymotion.com"
    private val videoIdRegex    = Regex("^[kx][a-zA-Z0-9]+$")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "getUrl called with url=$url")
        
        val (videoId, correctReferer) = parseVideoInfo(url) ?: run {
            Log.e(TAG, "Failed to parse video info from: $url")
            return
        }
        
        Log.i(TAG, "Parsed videoId=$videoId, referer=$correctReferer")

        val metaDataUrl = "$baseUrl/player/metadata/video/$videoId"
        Log.d(TAG, "Fetching metadata from: $metaDataUrl")

        val response = try {
            app.get(
                metaDataUrl,
                referer  = correctReferer,
                headers  = mapOf(
                    "Origin" to baseUrl, 
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9,id;q=0.8"
                )
            ).text
        } catch (e: Exception) {
            Log.e(TAG, "Network request failed: ${e.message}")
            return
        }
        
        Log.d(TAG, "Metadata response length: ${response.length}")
        
        val meta = try {
            parseJson<DmMetaData>(response)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse failed: ${e.message}")
            Log.d(TAG, "Raw response (first 500 chars): ${response.take(500)}")
            return
        }
        
        meta.message?.let {
            Log.e(TAG, "Dailymotion error message: $it")
            return
        }

        val qualities = meta.qualities
        if (qualities == null || qualities.isEmpty()) {
            Log.e(TAG, "No qualities found in metadata!")
            Log.d(TAG, "Full metadata: $response")
            return
        }
        
        Log.i(TAG, "Qualities keys found: ${qualities.keys.joinToString()}")

        var streamCount = 0
        
        // First, try to get variants from "auto" quality HLS manifest
        val autoQuality = qualities["auto"] ?: qualities.values.firstOrNull()
        val autoEntry = autoQuality?.firstOrNull { it.url?.contains(".m3u8") == true }
        
        if (autoEntry?.url != null) {
            val autoM3u8Url = autoEntry.url!!
            Log.d(TAG, "Found auto m3u8: ${autoM3u8Url.take(80)}...")
            
            // Parse HLS manifest for quality variants
            val variants = parseHlsManifest(autoM3u8Url, correctReferer)
            
            if (variants.isNotEmpty()) {
                Log.i(TAG, "Found ${variants.size} quality variants from HLS manifest")
                
                for (variant in variants) {
                    val qualityInt = resolutionToQuality(variant.resolution)
                    val qualityLabel = qualityIntToLabel(qualityInt)
                    
                    Log.d(TAG, "  Variant: ${variant.bandwidth}bps, res=${variant.resolution}, quality=$qualityLabel")
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name   = "$name ($qualityLabel)",
                            url    = variant.url,
                            type   = ExtractorLinkType.M3U8
                        ) {
                            this.quality = qualityInt
                            this.referer = correctReferer
                        }
                    )
                    streamCount++
                }
            } else {
                Log.w(TAG, "No variants found in manifest, using direct URL")
                // Fallback to direct URL with auto quality
                addStreamCallback(callback, autoM3u8Url, "auto", correctReferer)
                streamCount++
            }
        }
        
        // Also check for non-auto quality keys (direct mp4 URLs)
        for ((key, entries) in qualities) {
            if (key == "auto") continue
            
            for (entry in entries) {
                val entryUrl = entry.url ?: continue
                
                // Skip m3u8 as we've already processed them
                if (entryUrl.contains(".m3u8")) continue
                
                // Process direct MP4 URLs
                if (entryUrl.contains(".mp4")) {
                    val qualityInt = qualityKeyToEnum(key)
                    val qualityLabel = qualityIntToLabel(qualityInt)
                    
                    Log.d(TAG, "  Direct MP4: $key -> ${entryUrl.take(80)}...")
                    
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name   = "$name ($qualityLabel)",
                            url    = entryUrl,
                            type   = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = qualityInt
                            this.referer = correctReferer
                        }
                    )
                    streamCount++
                }
            }
        }
        
        Log.i(TAG, "Total streams found: $streamCount")
        
        if (streamCount == 0) {
            Log.e(TAG, "WARNING: No streams found!")
        }

        // Subtitles
        meta.subtitles?.data?.forEach { (lang, subData) ->
            subData.urls.forEach { subUrl ->
                Log.d(TAG, "Found subtitle: $lang - $subUrl")
                subtitleCallback(newSubtitleFile(subData.label, subUrl))
            }
        }
    }
    
    /**
     * Parse HLS manifest to extract quality variants
     * Returns list of variants with bandwidth, resolution, and URL
     */
    private suspend fun parseHlsManifest(manifestUrl: String, referer: String): List<HlsVariant> {
        Log.d(TAG, "Parsing HLS manifest: ${manifestUrl.take(80)}...")
        
        val variants = mutableListOf<HlsVariant>()
        
        try {
            val manifestResponse = app.get(
                manifestUrl,
                headers = mapOf(
                    "Referer" to referer,
                    "Origin" to "https://www.dailymotion.com"
                )
            ).text
            
            Log.d(TAG, "Manifest content length: ${manifestResponse.length}")
            Log.d(TAG, "Manifest preview: ${manifestResponse.take(300)}")
            
            // Parse #EXT-X-STREAM-INF lines
            val streamInfRegex = Regex("""#EXT-X-STREAM-INF:([^,\n]+)[\n]([^\n]+)""")
            
            streamInfRegex.findAll(manifestResponse).forEach { match ->
                val attributes = match.groupValues[1]
                val url = match.groupValues[2].trim()
                
                // Extract BANDWIDTH
                val bandwidthMatch = Regex("""BANDWIDTH=(\d+)""").find(attributes)
                val bandwidth = bandwidthMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                
                // Extract RESOLUTION
                val resolutionMatch = Regex("""RESOLUTION=(\d+x\d+)""").find(attributes)
                val resolution = resolutionMatch?.groupValues?.get(1)
                
                if (url.isNotBlank()) {
                    variants.add(HlsVariant(bandwidth, resolution, url))
                }
            }
            
            // If regex didn't work, try alternative parsing
            if (variants.isEmpty()) {
                Log.d(TAG, "Regex parsing failed, trying alternative method")
                
                val lines = manifestResponse.split("\n")
                var lastAttrLine = ""
                
                for (line in lines) {
                    if (line.startsWith("#EXT-X-STREAM-INF:")) {
                        lastAttrLine = line.removePrefix("#EXT-X-STREAM-INF:")
                    } else if (!line.startsWith("#") && line.isNotBlank() && lastAttrLine.isNotBlank()) {
                        val bandwidthMatch = Regex("""BANDWIDTH=(\d+)""").find(lastAttrLine)
                        val bandwidth = bandwidthMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        
                        val resolutionMatch = Regex("""RESOLUTION=(\d+x\d+)""").find(lastAttrLine)
                        val resolution = resolutionMatch?.groupValues?.get(1)
                        
                        variants.add(HlsVariant(bandwidth, resolution, line.trim()))
                        lastAttrLine = ""
                    }
                }
            }
            
            // Sort by bandwidth (highest first)
            variants.sortByDescending { it.bandwidth }
            
            Log.d(TAG, "Parsed ${variants.size} variants")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse HLS manifest: ${e.message}")
        }
        
        return variants
    }
    
    private fun resolutionToQuality(resolution: String?): Int {
        if (resolution == null) return Qualities.Unknown.value
        
        val height = resolution.substringAfter("x").substringBefore("?").toIntOrNull() ?: return Qualities.Unknown.value
        
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
    
    private fun qualityKeyToEnum(key: String): Int {
        return when (key) {
            "1080" -> Qualities.P1080.value
            "720"  -> Qualities.P720.value
            "480"  -> Qualities.P480.value
            "380"  -> Qualities.P360.value  // Dailymotion uses 380 for 360p
            "240"  -> Qualities.P240.value
            "144"  -> Qualities.P144.value
            else   -> Qualities.Unknown.value
        }
    }
    
    private fun qualityIntToLabel(qualityInt: Int): String {
        return when (qualityInt) {
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
    }
    
    private suspend fun addStreamCallback(
        callback: (ExtractorLink) -> Unit,
        url: String,
        qualityKey: String,
        referer: String
    ) {
        val qualityInt = qualityKeyToEnum(qualityKey)
        val qualityLabel = qualityIntToLabel(qualityInt)
        
        callback.invoke(
            newExtractorLink(
                source = name,
                name   = "$name ($qualityLabel)",
                url    = url,
                type   = ExtractorLinkType.M3U8
            ) {
                this.quality = qualityInt
                this.referer = referer
            }
        )
    }

    // ── URL parsing ───────────────────────────────────────────

    private fun parseVideoInfo(url: String): Pair<String, String>? {
        val s = url.trim()
        Log.d(TAG, "parseVideoInfo: $s")

        if (s.matches(videoIdRegex)) {
            Log.d(TAG, "  -> Raw video ID: $s")
            return Pair(s, "$baseUrl/embed/video/$s")
        }

        if ("geo.dailymotion.com" in s) {
            val vid = Regex("""[?&]video=([kx][a-zA-Z0-9]+)""").find(s)
                ?.groupValues?.get(1)
                ?: s.substringAfterLast("/").replace(".html", "")
                    .takeIf { it.matches(videoIdRegex) }
                ?: return null.also { Log.w(TAG, "  -> Cannot extract video ID from geo URL") }
            Log.d(TAG, "  -> Geo URL, video ID: $vid")
            return Pair(vid, s)
        }

        if ("dailymotion.com" in s) {
            val vid = Regex("""/video/([kx][a-zA-Z0-9]+)""").find(s)
                ?.groupValues?.get(1)
                ?: return null.also { Log.w(TAG, "  -> Cannot extract video ID from dailymotion URL") }
            Log.d(TAG, "  -> Dailymotion URL, video ID: $vid")
            return Pair(vid, "$baseUrl/embed/video/$vid")
        }

        Log.w(TAG, "  -> URL format not recognized")
        return null
    }
    
    companion object {
        private const val TAG = "CustomDailymotion"
    }
}
