package app.noam.patches.geocaching.search

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.noam.patches.geocaching.misc.extension.extensionHookPatch
import app.noam.patches.geocaching.shared.Constants

/** GeocacheApi.search (/mobile/v2/map/search), which caps the results for Basic members. */
internal object GeocacheSearchFingerprint : Fingerprint(
    definingClass = "Lcom/groundspeak/geocaching/intro/network/api/geocaches/b;",
    name = "k",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Lce4;", "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"),
)

internal val searchReplacePatch = bytecodePatch(
    name = "Map search from geocaching.com",
    description = "Map caches come from geocaching.com's search the way c:geo gets them, without the app's cap " +
        "and with every map filter applied.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(extensionHookPatch)

    execute {
        // Suspend function: p1 = search params, p2 = continuation, resumed by the extension off-thread.
        GeocacheSearchFingerprint.method.replaceInstructions(
            0,
            """
                invoke-static { p1, p2 }, ${Constants.GC_LIVE_SEARCH}->searchAsync(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                return-object v0
            """,
        )
    }
}
