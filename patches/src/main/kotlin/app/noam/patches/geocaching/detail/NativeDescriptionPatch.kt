package app.noam.patches.geocaching.detail

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.noam.patches.geocaching.misc.extension.extensionHookPatch
import app.noam.patches.geocaching.shared.Constants
import app.noam.patches.geocaching.shared.enableFeature

/** The description dialog's state handler (r64.y); its loaded state carries the short and long html. */
internal object DescriptionStateFingerprint : Fingerprint(
    definingClass = "Lr64;",
    name = "y",
    returnType = "V",
    parameters = listOf("Lx64;"),
)

internal val nativeDescriptionPatch = bytecodePatch(
    name = "Native description viewer",
    description = "Shows cache descriptions in one native viewer with their formatting, images, tables and buttons, " +
        "instead of the Web View and Text View tabs.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(extensionHookPatch)

    execute {
        DescriptionStateFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p0 .. p1 }, ${Constants.GC_DESCRIPTION_VIEW}->show(Ljava/lang/Object;Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :noam_app_view
                    return-void
                """,
                ExternalLabel("noam_app_view", getInstruction(0)),
            )
        }
    }
}

internal val descriptionLinksInAppPatch = bytecodePatch(
    name = "Open description links in the app",
    description = "Checker links and buttons (GeoCheck, Certitude...) and other description links open in a page " +
        "inside the app instead of the browser.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(nativeDescriptionPatch)

    execute {
        enableFeature("linksInApp")
    }
}
