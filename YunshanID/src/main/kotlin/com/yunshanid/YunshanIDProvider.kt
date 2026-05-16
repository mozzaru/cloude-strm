package com.yunshanid

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YunshanIDProvider: Plugin() {
    override fun load() {
        registerMainAPI(YunshanID())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(GdriveExtractor())
        registerExtractorAPI(OkRu())
    }
}
