package com.donghuaarena

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuaArenaProvider: Plugin() {
    override fun load() {
        registerMainAPI(DonghuaArena())
        registerExtractorAPI(StreamHls())
        registerExtractorAPI(LuluVdo())
        registerExtractorAPI(LuluVid())
        registerExtractorAPI(MyVidPlay())
        registerExtractorAPI(Byse())
        registerExtractorAPI(TurboVid())
        registerExtractorAPI(Vidara())
        registerExtractorAPI(Playmogo())
        registerExtractorAPI(ArchiveOrg())
        registerExtractorAPI(DTube())
    }
}
