package com.donghub

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DonghubProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(Donghub())
        registerExtractorAPI(InternetArchive())
        registerExtractorAPI(DtubeExtractor())
        registerExtractorAPI(CustomGeoDailymotion())
        registerExtractorAPI(CustomDailymotion())
        registerExtractorAPI(RpmvidExtractor())
        registerExtractorAPI(MegaNzExtractor())
    }
}
