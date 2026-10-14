package app.noam.patches.geocaching.shared

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/** The app's one "is Premium" check on the signed-in user. */
private const val IS_PREMIUM = "Ll3c;->d()Z"

/**
 * Makes the "is Premium" checks in [method] (from instruction [after], at most [limit] of them) come
 * out true. const/16 because const/4 only reaches v0-v15 and the patcher drops what it can't encode.
 */
internal fun forcePremium(method: MutableMethod, after: Int = 0, limit: Int = Int.MAX_VALUE): Int {
    val calls = method.instructions.withIndex().filter { (i, it) ->
        i >= after && it.opcode == Opcode.INVOKE_VIRTUAL &&
            (it as ReferenceInstruction).reference.toString() == IS_PREMIUM
    }.map { it.index }.take(limit)
    var forced = 0
    for (index in calls.reversed()) {
        val result = method.getInstruction(index + 1)
        if (result.opcode != Opcode.MOVE_RESULT) continue
        method.addInstructions(index + 2, "const/16 v${(result as OneRegisterInstruction).registerA}, 0x1")
        forced++
    }
    return forced
}

/** Every "is Premium" check in [clazz] comes out true. */
internal fun BytecodePatchContext.forcePremiumIn(clazz: String, methodName: String? = null) {
    val forced = mutableClassDefBy(clazz).methods
        .filter { it.implementation != null && (methodName == null || it.name == methodName) }
        .sumOf { forcePremium(it) }
    require(forced > 0) { "No Premium check found in $clazz${methodName?.let { ".$it" } ?: ""}" }
}

/** Turns on one of the extension's optional features (a GcFeatures method). */
internal fun BytecodePatchContext.enableFeature(name: String) {
    mutableClassDefBy(Constants.GC_FEATURES).methods.first { it.name == name }.replaceInstructions(
        0,
        """
            const/4 v0, 0x1
            return v0
        """,
    )
}
