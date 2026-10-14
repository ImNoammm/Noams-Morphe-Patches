package app.noam.patches.geocaching.offline

import app.morphe.patcher.patch.bytecodePatch
import app.noam.patches.geocaching.detail.detailEnrichPatch
import app.noam.patches.geocaching.search.searchReplacePatch
import app.noam.patches.geocaching.shared.Constants
import app.noam.patches.geocaching.shared.enableFeature

internal val offlineCachesPatch = bytecodePatch(
    name = "Offline caches",
    description = "Keeps every cache the map shows (the 6000 most recent) and every cache you open, with its images " +
        "(the 300 most recent), on the device, so the map and the caches work without a connection.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(searchReplacePatch, detailEnrichPatch)

    execute {
        enableFeature("offlineMapCaches")
        enableFeature("offlineCacheCopies")
    }
}
