package com.donghuaarena

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
class DonghuaArenaProvider: Plugin() {
    override fun load() {
        registerMainAPI(DonghuaArena())

        // Site specific or custom
        registerExtractorAPI(StreamHls())
        registerExtractorAPI(LuluVdo())
        registerExtractorAPI(LuluVid())
        registerExtractorAPI(LuluStream())
        registerExtractorAPI(MyVidPlay())
        registerExtractorAPI(TurboVid())
        registerExtractorAPI(Vidara())
        registerExtractorAPI(VidaraSo())
        registerExtractorAPI(Playmogo())

        // Explicitly register common core extractors for better compatibility
        registerExtractorAPI(DoodLaExtractor())
        registerExtractorAPI(DoodToExtractor())
        registerExtractorAPI(DoodSoExtractor())
        registerExtractorAPI(DoodstreamCom())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(MixDrop())

        // Byse variants from core
        registerExtractorAPI(Bysezejataos())
        registerExtractorAPI(ByseBuho())
        registerExtractorAPI(ByseQekaho())
        registerExtractorAPI(ByseVepoin())

        // VidHide from core
        registerExtractorAPI(VidhideExtractor())
        registerExtractorAPI(VidHidePro())

        registerExtractorAPI(ArchiveOrg())
        registerExtractorAPI(DTube())
    }
}
