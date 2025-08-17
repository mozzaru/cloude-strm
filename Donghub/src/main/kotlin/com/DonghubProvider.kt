package com.donghub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghubProvider: Plugin() {
    override fun load() {
        registerMainAPI(Donghub())
        registerExtractorAPI(ArchiveOrgExtractor())
    }
}
