package com.donghub

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
class DonghubProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(Donghub())
        registerExtractorAPI(InternetArchive())
        registerExtractorAPI(DtubeExtractor())
        //registerExtractorAPI(CustomGeoDailymotion())
        //registerExtractorAPI(CustomDailymotion())
        registerExtractorAPI(RpmvidExtractor())
        //registerExtractorAPI(MegaNzExtractor())
    }
}
