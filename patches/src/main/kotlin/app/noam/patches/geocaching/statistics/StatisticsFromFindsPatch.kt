package app.noam.patches.geocaching.statistics

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.noam.patches.geocaching.misc.extension.extensionHookPatch
import app.noam.patches.geocaching.shared.Constants
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private object UserStatisticsFingerprint : Fingerprint(
    definingClass = "Lcom/groundspeak/geocaching/intro/network/api/stats/b;",
    name = "a",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/String;", "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"),
)

/** Part of Unlock Statistics: the numbers are worked out from your own finds list. */
internal val statisticsFromFindsPatch = bytecodePatch(
    description = "Works Statistics out from your own finds list.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(extensionHookPatch)

    execute {
        // The finds request needs the app's API client id: the string the stats call sends after "Consumer-Key".
        val strings = UserStatisticsFingerprint.method.instructions
            .filter { it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO }
            .map { ((it as ReferenceInstruction).reference as StringReference).string }
        val consumerKey = strings.getOrNull(strings.indexOf("Consumer-Key") + 1)
        require(strings.contains("Consumer-Key") && !consumerKey.isNullOrEmpty()) { "App API client id not found" }
        mutableClassDefBy(Constants.GC_USER_STATS).methods.first { it.name == "consumerKey" }.replaceInstructions(
            0,
            """
                const-string v0, "$consumerKey"
                return-object v0
            """,
        )

        UserStatisticsFingerprint.method.replaceInstructions(
            0,
            """
                invoke-static { p2 }, ${Constants.GC_USER_STATS}->statsAsync(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                return-object v0
            """,
        )
    }
}
