package app.noam.patches.geocaching.misc.extension

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.noam.patches.geocaching.shared.Constants
import com.android.tools.smali.dexlib2.Opcode

internal object GeoApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/groundspeak/geocaching/intro/GeoApplication;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
)

/** Gives the extension the app's context. */
internal val extensionHookPatch = bytecodePatch(
    description = "Connects the Morphe extension to the app.",
) {
    extendWith(Constants.EXTENSION_FILE)

    execute {
        GeoApplicationOnCreateFingerprint.method.apply {
            val superCall = instructions.indexOfFirst { it.opcode == Opcode.INVOKE_SUPER }
            addInstruction(
                superCall + 1,
                "invoke-static { p0 }, ${Constants.UTILS}->onApplicationCreate(Landroid/app/Application;)V",
            )
        }
    }
}
