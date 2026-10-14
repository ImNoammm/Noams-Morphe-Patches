package app.noam.patches.geocaching.templates

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.noam.patches.geocaching.misc.extension.extensionHookPatch
import app.noam.patches.geocaching.shared.Constants

private const val TEMPLATE_API = "Lcom/groundspeak/geocaching/intro/network/api/templates/a;"

/** Part of Unlock Templates: Templates live in the app's own local database instead of the Premium-only server. */
internal val localTemplatesPatch = bytecodePatch(
    description = "Keeps log Templates on the device.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(extensionHookPatch)

    execute {
        val api = mutableClassDefBy(TEMPLATE_API)
        val calls = mapOf(
            "a" to "invoke-static/range { p1 .. p2 }, ${Constants.GC_LOCAL_TEMPLATES}->create(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;",
            "b" to "invoke-static/range { p1 .. p1 }, ${Constants.GC_LOCAL_TEMPLATES}->delete(I)Ljava/lang/Object;",
            "c" to "invoke-static { }, ${Constants.GC_LOCAL_TEMPLATES}->getAll()Ljava/lang/Object;",
            "d" to "invoke-static/range { p1 .. p3 }, ${Constants.GC_LOCAL_TEMPLATES}->update(ILjava/lang/String;Ljava/lang/String;)Ljava/lang/Object;",
        )
        for ((name, call) in calls) {
            val method = api.methods.single { it.name == name && it.returnType == "Ljava/lang/Object;" }
            method.replaceInstructions(
                0,
                """
                    $call
                    move-result-object v0
                    return-object v0
                """,
            )
        }
    }
}
