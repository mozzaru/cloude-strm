package com.yunshanid

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
class YunshanIDProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(YunshanID())
        registerExtractorAPI(GdriveExtractor())
    }
}
