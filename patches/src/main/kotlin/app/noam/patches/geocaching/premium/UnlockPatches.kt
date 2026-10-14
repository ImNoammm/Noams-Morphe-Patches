package app.noam.patches.geocaching.premium

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.noam.patches.geocaching.search.searchReplacePatch
import app.noam.patches.geocaching.settings.localExperimentsPatch
import app.noam.patches.geocaching.shared.Constants
import app.noam.patches.geocaching.shared.forcePremium
import app.noam.patches.geocaching.shared.forcePremiumIn
import app.noam.patches.geocaching.statistics.statisticsFromFindsPatch
import app.noam.patches.geocaching.templates.localTemplatesPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val SETTINGS_FRAGMENT = "Lcom/groundspeak/geocaching/intro/fragments/settings/SettingsFragment;"
private const val TEMPLATES_FLAG =
    "Lcom/groundspeak/geocaching/intro/analytics/launchdarkly/LaunchDarklyFlag;->W:Lcom/groundspeak/geocaching/intro/analytics/launchdarkly/LaunchDarklyFlag;"

/** Settings rows pass "show the Premium badge" as the fifth constructor argument, in v5. */
private fun app.morphe.patcher.patch.BytecodePatchContext.hideSettingsRowBadge(row: String) {
    val init = mutableClassDefBy(row).methods.first { it.name == "<init>" && it.parameters.isEmpty() }
    val call = init.instructions.indexOfFirst { it.opcode == Opcode.INVOKE_DIRECT_RANGE }
    require(call >= 0) { "Settings row constructor call not found in $row" }
    init.addInstructions(call, "const/4 v5, 0x0")
}

internal val unlockMapFiltersPatch = bytecodePatch(
    name = "Unlock map filters",
    description = "Opens the map filter button and every filter option, including Fill in your D/T grid, without " +
        "Premium. The geocaching.com map search applies them.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(searchReplacePatch)

    execute {
        // The map menu's filter button, the filter bar, the filter screen and the saved-filter store.
        forcePremiumIn("Lcom/groundspeak/geocaching/intro/mainmap/map/k;", "c")
        forcePremiumIn("Lcom/groundspeak/geocaching/intro/mainmap/map/e;", "c")
        forcePremiumIn("Lhc4;", "d")
        forcePremiumIn("Lcom/groundspeak/geocaching/intro/geocachefilter/FilterViewModel;")
        forcePremiumIn("Lcom/groundspeak/geocaching/intro/geocachefilter/FilterViewModel\$populatePrefs\$1;")
        forcePremiumIn("Ldk3;")
        forcePremiumIn("Lak3;")
    }
}

internal val unlockStatisticsPatch = bytecodePatch(
    name = "Unlock Statistics",
    description = "Statistics (D/T grid, averages, streaks) worked out from your own finds, opened from the profile " +
        "without Premium and without the Go Premium line under it.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(statisticsFromFindsPatch)

    execute {
        forcePremiumIn("Lcom/groundspeak/geocaching/intro/profile/ProfileFragment\$onCreateView\$1\$1\$1\$1\$1;", "f")
        forcePremiumIn("Lcom/groundspeak/geocaching/intro/profile/o;")
    }
}

internal val unlockTemplatesPatch = bytecodePatch(
    name = "Unlock Templates",
    description = "Log Templates kept on the device, opened from Settings and from the Templates button when you " +
        "log a cache, without Premium.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(localTemplatesPatch)

    execute {
        forcePremiumIn(SETTINGS_FRAGMENT, "x")
        hideSettingsRowBadge("Lcom/groundspeak/geocaching/intro/fragments/settings/b0;")
        // The log screen's template icon: TEMPLATES_RELEASE flag && ... && isPremium. Only the check
        // after the flag is forced; the one before it is the favorite point icon.
        var forced = 0
        for (method in mutableClassDefBy("Lcom/groundspeak/geocaching/intro/loggeocache/LogGeocacheViewModel;").methods) {
            if (method.implementation == null) continue
            val flag = method.instructions.indexOfFirst {
                it.opcode == Opcode.SGET_OBJECT && (it as ReferenceInstruction).reference.toString() == TEMPLATES_FLAG
            }
            if (flag >= 0) forced += forcePremium(method, after = flag, limit = 1)
        }
        require(forced > 0) { "Template icon Premium check not found" }
    }
}

internal val unlockExperimentalFeaturesPatch = bytecodePatch(
    name = "Unlock Experimental features",
    description = "Opens Experimental features in Settings without Premium and keeps its toggles on after the app " +
        "restarts.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(localExperimentsPatch)

    execute {
        forcePremiumIn(SETTINGS_FRAGMENT, "y")
        hideSettingsRowBadge("Lcom/groundspeak/geocaching/intro/fragments/settings/p;")
    }
}

internal val unlockSortingPatch = bytecodePatch(
    name = "Unlock Finds and Hides sorting",
    description = "Every sort option in Finds and Hides works without Premium.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        forcePremiumIn("Lcom/groundspeak/geocaching/intro/profile/hidesandfinds/FindsViewModel;")
        forcePremiumIn("Lcom/groundspeak/geocaching/intro/profile/hidesandfinds/HidesViewModel;")
    }
}

internal val hideUpgradeBadgesPatch = bytecodePatch(
    name = "Hide Upgrade badges",
    description = "Removes the Upgrade badge from the map and the Premium badge from the Trails map layer.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    execute {
        forcePremiumIn("Lcom/groundspeak/geocaching/intro/mainmap/map/SharedMapViewModel;", "<init>")
        forcePremiumIn("Lcom/groundspeak/geocaching/intro/mainmap/map/SharedMapViewModel;", "A")
        // The Trails layer's Premium flag (MapType.E) is stored as false.
        mutableClassDefBy("Lcom/groundspeak/geocaching/intro/map/type/MapType;").methods
            .first { it.name == "<init>" && it.parameters.size == 10 }.apply {
                val store = instructions.indexOfFirst {
                    it.opcode == Opcode.IPUT_BOOLEAN &&
                        (it as ReferenceInstruction).reference.toString() == "Lcom/groundspeak/geocaching/intro/map/type/MapType;->E:Z"
                }
                require(store >= 0) { "Map layer Premium flag not found" }
                addInstructions(store, "const/16 v${getInstruction<TwoRegisterInstruction>(store).registerA}, 0x0")
            }
    }
}
