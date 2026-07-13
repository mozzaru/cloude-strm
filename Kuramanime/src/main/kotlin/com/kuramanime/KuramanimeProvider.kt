package com.kuramanime

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
class KuramanimeProvider: BasePlugin() {
    override fun load() {
        registerMainAPI(Kuramanime())
        registerExtractorAPI(Nyomo())
        registerExtractorAPI(Streamhide())
        registerExtractorAPI(Kuramadrive())
        registerExtractorAPI(Lbx())
        registerExtractorAPI(Sunrong())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(FileMoonIn())
        registerExtractorAPI(RPMShare())
        registerExtractorAPI(StreamP2P())
        registerExtractorAPI(Doodstream())
    }
}
