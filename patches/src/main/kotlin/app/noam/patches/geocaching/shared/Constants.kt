package app.noam.patches.geocaching.shared

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    const val GEOCACHING_PACKAGE = "com.groundspeak.geocaching.intro"

    // The only version the fingerprints are verified against (versionCode 1537).
    const val GEOCACHING_VERSION = "10.21.0"

    val COMPATIBILITY = Compatibility(
        packageName = GEOCACHING_PACKAGE,
        name = "Geocaching",
        targets = listOf(AppTarget(version = GEOCACHING_VERSION)),
    )

    const val EXTENSION_FILE = "extensions/geocaching.mpe"
    const val EXTENSION_PACKAGE = "Lapp/noam/extension/geocaching"

    const val UTILS = "$EXTENSION_PACKAGE/Utils;"
    const val GC_LIVE_DETAIL = "$EXTENSION_PACKAGE/GcLiveDetail;"
    const val GC_LIVE_SEARCH = "$EXTENSION_PACKAGE/GcLiveSearch;"
    const val GC_OFFLINE_STORE = "$EXTENSION_PACKAGE/GcOfflineStore;"
    const val GC_OFFLINE_LIST = "$EXTENSION_PACKAGE/GcOfflineList;"
    const val GC_OFFLINE_LIST_UI = "$EXTENSION_PACKAGE/GcOfflineListUi;"
    const val GC_USER_STATS = "$EXTENSION_PACKAGE/GcUserStats;"
    const val GC_LOCAL_TEMPLATES = "$EXTENSION_PACKAGE/GcLocalTemplates;"
    const val GC_DESCRIPTION_VIEW = "$EXTENSION_PACKAGE/GcDescriptionView;"
    const val GC_LOCAL_LISTS = "$EXTENSION_PACKAGE/GcLocalLists;"
    const val GC_MAP_STYLES = "$EXTENSION_PACKAGE/GcMapStyles;"
    const val GC_MAP_VIEW = "$EXTENSION_PACKAGE/GcMapView;"
    const val GC_IMAGE_SAVE = "$EXTENSION_PACKAGE/GcImageSave;"
    const val GC_LIVE_SESSION = "$EXTENSION_PACKAGE/GcLiveSession;"
    const val GC_FEATURES = "$EXTENSION_PACKAGE/GcFeatures;"
}
