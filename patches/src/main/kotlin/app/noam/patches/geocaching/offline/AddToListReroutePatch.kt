package app.noam.patches.geocaching.offline

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.noam.patches.geocaching.misc.extension.extensionHookPatch
import app.noam.patches.geocaching.shared.Constants
import com.android.tools.smali.dexlib2.Opcode

internal object AddToListOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/groundspeak/geocaching/intro/activities/AddGeocacheToListActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)

private object AddToListOnStartFingerprint : Fingerprint(
    definingClass = "Lcom/groundspeak/geocaching/intro/activities/AddGeocacheToListActivity;",
    name = "onStart",
    returnType = "V",
    parameters = emptyList(),
)

private object AddToListMenuFingerprint : Fingerprint(
    definingClass = "Lcom/groundspeak/geocaching/intro/activities/AddGeocacheToListActivity;",
    name = "onCreateOptionsMenu",
    returnType = "Z",
    parameters = listOf("Landroid/view/Menu;"),
)

private object AddToListPrepareMenuFingerprint : Fingerprint(
    definingClass = "Lcom/groundspeak/geocaching/intro/activities/AddGeocacheToListActivity;",
    name = "onPrepareOptionsMenu",
    returnType = "Z",
    parameters = listOf("Landroid/view/Menu;"),
)

/** onResume only calls this server-list refresh (onResume itself has no spare register). */
private object AddToListRefreshFingerprint : Fingerprint(
    definingClass = "Lcom/groundspeak/geocaching/intro/activities/AddGeocacheToListActivity;",
    name = "F",
    returnType = "V",
    parameters = emptyList(),
)

/** Every "Add to list" in the app opens AddGeocacheToListActivity with the cache codes as extras. */
internal val addToListReroutePatch = bytecodePatch(
    description = "Add to list saves the cache on the device, into the list you pick.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(extensionHookPatch)

    execute {
        AddToListOnCreateFingerprint.method.apply {
            val superIndex = instructions.indexOfFirst { it.opcode == Opcode.INVOKE_SUPER }
            require(superIndex >= 0) { "super.onCreate not found" }
            val continueAt = getInstruction(superIndex + 1)
            addInstructionsWithLabels(
                superIndex + 1,
                """
                    invoke-static { p0 }, ${Constants.GC_OFFLINE_LIST}->handleAddToList(Landroid/app/Activity;)Z
                    move-result v0
                    if-eqz v0, :noam_proceed
                    return-void
                """,
                ExternalLabel("noam_proceed", continueAt),
            )
        }

        // While the list picker is open, the activity's own onStart, onResume refresh and options
        // menu would use setup that onCreate skipped; they return early for claimed activities.
        val guard = """
            invoke-static/range { p0 .. p0 }, ${Constants.GC_OFFLINE_LIST}->isClaimed(Landroid/app/Activity;)Z
            move-result v0
            if-eqz v0, :noam_stock_lifecycle
            return-void
        """
        AddToListOnStartFingerprint.method.apply {
            val superIndex = instructions.indexOfFirst { it.opcode == Opcode.INVOKE_SUPER }
            require(superIndex >= 0) { "super.onStart not found" }
            addInstructionsWithLabels(superIndex + 1, guard, ExternalLabel("noam_stock_lifecycle", getInstruction(superIndex + 1)))
        }
        AddToListRefreshFingerprint.method.apply {
            addInstructionsWithLabels(0, guard, ExternalLabel("noam_stock_lifecycle", getInstruction(0)))
        }
        // AppCompat prepares the menu even when creating it returned false, so guard both.
        for (fingerprint in listOf(AddToListMenuFingerprint, AddToListPrepareMenuFingerprint)) {
            fingerprint.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static/range { p0 .. p0 }, ${Constants.GC_OFFLINE_LIST}->isClaimed(Landroid/app/Activity;)Z
                        move-result v0
                        if-eqz v0, :noam_stock_menu
                        const/4 v0, 0x0
                        return v0
                    """,
                    ExternalLabel("noam_stock_menu", getInstruction(0)),
                )
            }
        }
    }
}
