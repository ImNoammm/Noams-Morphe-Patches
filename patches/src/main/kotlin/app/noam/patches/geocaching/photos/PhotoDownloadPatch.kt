package app.noam.patches.geocaching.photos

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.noam.patches.geocaching.misc.extension.extensionHookPatch
import app.noam.patches.geocaching.shared.Constants

internal object ImageGalleryMenuFingerprint : Fingerprint(
    definingClass = "Lcom/groundspeak/geocaching/intro/activities/ImageGalleryActivity;",
    name = "onCreateOptionsMenu",
    returnType = "Z",
    parameters = listOf("Landroid/view/Menu;"),
)

/** A Download button in the photo viewer (log and cache photos), saving to Pictures/Geocaching. */
internal val photoDownloadPatch = bytecodePatch(
    name = "Download photos",
    description = "Adds a Download button to the photo viewer that saves the photo to Pictures/Geocaching.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(extensionHookPatch)

    execute {
        ImageGalleryMenuFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p0 .. p1 }, ${Constants.GC_IMAGE_SAVE}->addMenu(Landroid/app/Activity;Landroid/view/Menu;)V",
        )
    }
}
