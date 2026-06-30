package com.donghub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.math.BigInteger
import com.donghub.MegaNzExtractor

/**
 * DtubeExtractor - Fixed Version
 * ==============
 * Improvements:
 * - Enhanced logging for debugging
 * - Better URL parsing
 * - Proper referer handling for both CDN
 * - Fallback URLs for resilience
 */
open class DtubeExtractor : ExtractorApi() {
    override var mainUrl = "https://play.d.tube"
    override val requiresReferer = true
    override var name = "DTube"

    companion object {
        private const val TAG = "DtubeExtractor"
        private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    }

    private fun base58ToUuid(base58: String): String {
        Log.d(TAG, "Converting base58 to UUID: $base58")
        var n = BigInteger.ZERO
        for (char in base58) {
            val index = BASE58_ALPHABET.indexOf(char)
            if (index == -1) {
                Log.w(TAG, "Invalid base58 character: $char")
                return ""
            }
            n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(index.toLong()))
        }
        val hex = n.toString(16).padStart(32, '0')
        if (hex.length < 32) {
            Log.w(TAG, "Hex too short: $hex")
            return ""
        }
        val uuid = "${hex.substring(0,8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20,32)}"
        Log.d(TAG, "Converted UUID: $uuid")
        return uuid
    }

    private fun isUuid(id: String) = id.matches(
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            RegexOption.IGNORE_CASE)
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "getUrl called with url=$url, referer=$referer")

        MegaNzExtractor.stopAll()
    
        // Parse raw base58 ID from URL
        // Examples:
        //   https://play.d.tube/?v=BXC71sLPVuRu72fuZ8K3hd
        //   https://play.d.tube?v=BXC71sLPVuRu72fuZ8K3hd
        //   https://d.tube/watch?v=BXC71sLPVuRu72fuZ8K3hd
        //   BXC71sLPVuRu72fuZ8K3hd
        
        val rawId = when {
            "?v=" in url -> url.substringAfter("?v=").substringBefore("&")
            "v=" in url && "?v=" !in url -> url.substringAfter("v=").substringBefore("&")
            else -> url.substringAfterLast("/").substringBefore("?").substringBefore("&")
        }.trim()
        
        Log.d(TAG, "Extracted rawId: $rawId")
        
        if (rawId.isBlank()) {
            Log.e(TAG, "Cannot extract video ID from URL: $url")
            return
        }
        
        // Check if already UUID format
        val isAlreadyUuid = isUuid(rawId)
        
        val videoId = if (isAlreadyUuid) {
            Log.d(TAG, "ID is already UUID format")
            rawId
        } else {
            base58ToUuid(rawId).also { 
                if (it.isBlank()) {
                    Log.e(TAG, "Failed to convert base58 to UUID")
                    return
                }
            }
        }
        
        // FIX: referer harus "https://play.d.tube/?v=<rawId>" (base58, bukan UUID)
        val correctReferer = if (!isAlreadyUuid) "$mainUrl/?v=$rawId" else "$mainUrl/"
        Log.d(TAG, "Using referer: $correctReferer")
        
        val headers = mapOf(
            "Referer"         to correctReferer,
            "Origin"          to mainUrl,
            "User-Agent"      to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36",
            "Accept"          to "*/*",
            "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
        )
        
        // Stop proxy Mega yang aktif supaya tidak conflic codec/state
        // ketika ExoPlayer init stream DTube baru → fix DECODER_INIT_FAILED
        MegaNzExtractor.stopAll()
        Log.i(TAG, "Testing DTube streams for videoId=$videoId")
        
        // Try both CDN endpoints
        val cdnEndpoints = listOf(
            "nas1.d.tube" to "nas1",
            "nas2.d.tube" to "nas2"
        )
        
        var foundCount = 0
        
        for ((cdnHost, label) in cdnEndpoints) {
            val m3u8Url = "https://$cdnHost/videos/$videoId/master.m3u8"
            Log.d(TAG, "Testing $label CDN: $m3u8Url")
            
            try {
                // Test if the stream is accessible
                val testResponse = app.get(
                    m3u8Url,
                    headers = headers,
                    allowRedirects = true
                )
                
                Log.d(TAG, "$label response status: ${testResponse.code}")
                
                if (testResponse.code in 200..299) {
                    Log.i(TAG, "✓ $label stream available!")
                    
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name",
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = correctReferer
                            this.headers = headers
                        }
                    )
                    foundCount++
                } else {
                    Log.w(TAG, "✗ $label returned status ${testResponse.code}")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "✗ $label request failed: ${e.message}")
            }
        }
        
        // If no streams found, try with direct video ID (no UUID conversion)
        if (foundCount == 0 && !isAlreadyUuid) {
            Log.w(TAG, "No streams found with UUID, trying direct base58 ID")
            
            val directUrl = "$mainUrl/videos/$rawId/master.m3u8"
            Log.d(TAG, "Trying direct URL: $directUrl")
            
            try {
                val response = app.get(
                    directUrl,
                    headers = headers,
                    allowRedirects = true
                )
                
                if (response.code in 200..299) {
                    Log.i(TAG, "✓ Direct stream available!")
                    
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name",
                            url = directUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = correctReferer
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Direct URL also failed: ${e.message}")
            }
        }
        
        if (foundCount == 0) {
            Log.e(TAG, "WARNING: No DTube streams found for video: $rawId")
        } else {
            Log.i(TAG, "Successfully found $foundCount DTube streams")
        }
    }
}
