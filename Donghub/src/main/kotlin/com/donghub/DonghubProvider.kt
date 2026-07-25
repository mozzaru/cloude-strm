package com.donghub

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
class DonghubProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(Donghub())
        registerExtractorAPI(InternetArchive())
        registerExtractorAPI(Dtube())
        registerExtractorAPI(RpmShare())
        registerExtractorAPI(DailymotionFixed())
    }
}
