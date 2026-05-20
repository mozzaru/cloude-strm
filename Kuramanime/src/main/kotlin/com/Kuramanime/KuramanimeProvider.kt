package com.Kuramanime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KuramanimeProvider: Plugin() {
    override fun load() {
        registerMainAPI(Kuramanime())
        registerExtractorAPI(KuramadriveS1())
        registerExtractorAPI(RPMShare())
    }
}
