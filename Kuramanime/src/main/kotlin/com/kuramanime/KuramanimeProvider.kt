package com.kuramanime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KuramanimeProvider: Plugin() {
    override fun load() {
        registerMainAPI(Kuramanime())
        registerExtractorAPI(Nyomo())
        registerExtractorAPI(Streamhide())
        registerExtractorAPI(Kuramadrive())
        registerExtractorAPI(Lbx())
        registerExtractorAPI(Sunrong())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(RPMShare())
        registerExtractorAPI(StreamP2P())
        registerExtractorAPI(Doodstream())
    }
}
