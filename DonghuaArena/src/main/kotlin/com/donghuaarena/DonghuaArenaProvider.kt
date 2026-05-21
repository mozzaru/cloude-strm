package com.donghuaarena

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.extractors.*

@CloudstreamPlugin
class DonghuaArenaProvider: Plugin() {
    override fun load() {
        registerMainAPI(DonghuaArena())

        // Register core extractors first
        registerExtractorAPI(Bysezejataos())
        registerExtractorAPI(ByseBuho())
        registerExtractorAPI(ByseQekaho())
        registerExtractorAPI(ByseVepoin())

        registerExtractorAPI(DoodLaExtractor())
        registerExtractorAPI(DoodToExtractor())
        registerExtractorAPI(DoodSoExtractor())
        registerExtractorAPI(DoodstreamCom())

        registerExtractorAPI(LuluStream())
        registerExtractorAPI(Lulustream1())
        registerExtractorAPI(Lulustream2())
        registerExtractorAPI(Luluvdoo())

        registerExtractorAPI(StreamTape())
        registerExtractorAPI(MixDrop())
        registerExtractorAPI(MixDropTo())
        registerExtractorAPI(VidhideExtractor())
        registerExtractorAPI(VidHidePro())

        // Site specific or custom fallbacks
        registerExtractorAPI(StreamHls())
        registerExtractorAPI(LuluVid())
        registerExtractorAPI(LuluVdo())
        registerExtractorAPI(TurboVid())
        registerExtractorAPI(Vidara())
        registerExtractorAPI(VidaraSo())
        registerExtractorAPI(Playmogo())
        registerExtractorAPI(MyVidPlay())
        registerExtractorAPI(ByseFallback())

        registerExtractorAPI(ArchiveOrg())
        registerExtractorAPI(DTube())
    }
}
