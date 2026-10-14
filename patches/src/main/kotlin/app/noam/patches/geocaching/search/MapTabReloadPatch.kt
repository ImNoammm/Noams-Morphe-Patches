package app.noam.patches.geocaching.search

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.noam.patches.geocaching.shared.Constants
import com.android.tools.smali.dexlib2.Opcode

/** The app rebuilds the map on every return to the Map tab; the old map's last frame covers the rebuild. */
internal val mapTabReloadPatch = bytecodePatch(
    name = "Faster map reloads after switching tabs",
    description = "Coming back to the Map tab shows the map as you left it right away while it redraws underneath.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(nativeMapPatch)

    execute {
        mutableClassDefBy(MAPLIBRE_FRAGMENT).methods.first { it.name == "onPause" }.apply {
            val superIndex = instructions.indexOfFirst { it.opcode == Opcode.INVOKE_SUPER }
            require(superIndex >= 0) { "Map onPause super call not found" }
            addInstructions(superIndex + 1, "invoke-static/range { p0 .. p0 }, ${Constants.GC_MAP_VIEW}->capture(Ljava/lang/Object;)V")
        }
        mutableClassDefBy(MAPLIBRE_FRAGMENT).methods.first { it.name == "onViewCreated" }.apply {
            val end = instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
            require(end >= 0) { "Map onViewCreated return not found" }
            addInstructions(end, "invoke-static/range { p0 .. p0 }, ${Constants.GC_MAP_VIEW}->cover(Ljava/lang/Object;)V")
        }
    }
}
