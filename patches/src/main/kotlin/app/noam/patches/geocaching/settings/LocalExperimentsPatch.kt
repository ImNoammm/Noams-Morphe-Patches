package app.noam.patches.geocaching.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.noam.patches.geocaching.misc.extension.extensionHookPatch
import app.noam.patches.geocaching.shared.Constants

/** SettingsApi GET /mobile/v1/user/settings/mobileexperiments. */
internal object GetMobileExperimentsFingerprint : Fingerprint(
    definingClass = "Lcom/groundspeak/geocaching/intro/network/api/settings/a;",
    name = "c",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Lkotlin/coroutines/jvm/internal/ContinuationImpl;"),
)

/** On launch the app replaces the toggles with the server's copy; "offline" makes it keep the local ones. */
internal val localExperimentsPatch = bytecodePatch(
    description = "Keeps the Experimental features toggles on the device.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(extensionHookPatch)

    execute {
        GetMobileExperimentsFingerprint.method.replaceInstructions(
            0,
            """
                invoke-static { }, ${Constants.UTILS}->offlineFailure()Ljava/lang/Object;
                move-result-object v0
                return-object v0
            """,
        )
    }
}
