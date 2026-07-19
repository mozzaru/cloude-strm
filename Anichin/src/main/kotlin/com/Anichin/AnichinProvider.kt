package com.Anichin

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnichinProvider: BasePlugin() {
    override fun load() {
        registerMainAPI(Anichin())
        registerExtractorAPI(OkRuSSL())
        registerExtractorAPI(OkRuHTTP())
        registerExtractorAPI(Rumble())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(svilla())
        registerExtractorAPI(svanila())
        registerExtractorAPI(Vidguardto())
        registerExtractorAPI(Vidguardto1())
        registerExtractorAPI(Vidguardto2())
        registerExtractorAPI(Vidguardto3())
        registerExtractorAPI(AnichinPlayer())
        registerExtractorAPI(Turbovidhls())
        registerExtractorAPI(RpmShare())
        registerExtractorAPI(VidHide())
    }
}
