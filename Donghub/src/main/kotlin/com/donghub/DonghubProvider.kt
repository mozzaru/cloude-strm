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
        registerExtractorAPI(RpmvidExtractor())
        registerExtractorAPI(MegaNzExtractor())
        // Donghub emits geo.dailymotion.com embeds. The stock extractor does not
        // consistently handle that URL shape, so register both URL variants.
        registerExtractorAPI(CustomGeoDailymotion())
        registerExtractorAPI(CustomDailymotion())
    }
}
