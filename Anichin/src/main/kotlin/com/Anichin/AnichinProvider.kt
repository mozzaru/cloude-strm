package com.Anichin

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnichinProvider: BasePlugin() {
    override fun load() {
        registerMainAPI(Anichin())
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
        registerExtractorAPI(Morencius())
        registerExtractorAPI(Dtube())
        // Fixed local copy of the core OkRu extractor: ok.ru now escapes its inline JSON with
        // plain `&quot;` (the core Odnoklassniki extractor only unescapes `\&quot;`), so this
        // registered copy must take priority for ok.ru URLs.
        registerExtractorAPI(OkRuSSL())
        // Resolves the "Dailymotion [ADS]" relay (player.nunadrama.sbs) to its embedded
        // geo.dailymotion.com player.
        registerExtractorAPI(Nunadrama())
    }
}
