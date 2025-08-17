package com.anichin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnichinProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Anichin())
        registerExtractorAPI(Rumble())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(StreamRubyx1())
        registerExtractorAPI(StreamRubyx2())

    }
}
