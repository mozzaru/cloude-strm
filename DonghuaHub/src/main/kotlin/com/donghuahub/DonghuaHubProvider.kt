package com.donghuahub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuaHubProvider: Plugin() {
    override fun load() {
        registerMainAPI(DonghuaHub())
        registerExtractorAPI(OkRu())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(GdriveExtractor())
    }
}
