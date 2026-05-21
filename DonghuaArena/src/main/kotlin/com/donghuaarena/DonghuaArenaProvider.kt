package com.donghuaarena

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuaArenaProvider: Plugin() {
    override fun load() {
        registerMainAPI(DonghuaArena())
    }
}
