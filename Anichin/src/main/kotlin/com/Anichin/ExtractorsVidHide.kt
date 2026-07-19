package com.Anichin

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class VidHide : ExtractorApi() {
    override val name = "VidHide"
    override val mainUrl = "https://minochinos.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("VidHide", "Fetching: $url")
        val response = app.get(url, referer = referer ?: "https://anichin.moe/")
        val html = response.text

        val m3u8Regex = Regex("""https?://[^\s"'<>]*\.m3u8[^\s"'<>]*""")
        val directM3u8 = m3u8Regex.find(html)
        if (directM3u8 != null) {
            val m3u8Url = directM3u8.value
            Log.d("VidHide", "Found direct m3u8: ${m3u8Url.take(80)}...")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        val packedRegex = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{[^}]+}\((['"])([^]*?)\1,(\d+),(\d+),(['"])([^]*?)\5"""
        )
        val packedMatch = packedRegex.find(html)

        if (packedMatch != null) {
            val p = packedMatch.groupValues[2]
            val a = packedMatch.groupValues[3].toIntOrNull() ?: 36
            val c = packedMatch.groupValues[4].toIntOrNull() ?: 0
            val k = packedMatch.groupValues[6].split("|")

            val unpacked = unpackPacker(p, a, c, k)
            Log.d("VidHide", "Unpacked JS length: ${unpacked.length}")

            val m3u8InUnpacked = m3u8Regex.find(unpacked)
            if (m3u8InUnpacked != null) {
                val m3u8Url = m3u8InUnpacked.value
                Log.d("VidHide", "Found m3u8 from packed JS: ${m3u8Url.take(80)}...")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return
            }
        }

        Log.w("VidHide", "No m3u8 found")
    }

    private fun unpackPacker(p: String, a: Int, c: Int, k: List<String>): String {
        var result = p
        for (i in (c - 1) downTo 0) {
            val word = k.getOrNull(i) ?: continue
            if (word.isBlank()) continue
            val base36 = i.toString(a)
            result = result.replace(Regex("\\b$base36\\b"), word)
        }
        return result
    }
}
