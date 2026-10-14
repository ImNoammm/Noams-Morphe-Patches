package app.noam.patches.geocaching.login

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.noam.patches.geocaching.misc.extension.extensionHookPatch
import app.noam.patches.geocaching.shared.Constants

/** ProfileApi.login: POST /mobile/v1/_users/login with the handle and password typed in the app. */
internal object AppLoginFingerprint : Fingerprint(
    definingClass = "Lcom/groundspeak/geocaching/intro/network/api/profile/a;",
    name = "b",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "Lcom/groundspeak/geocaching/intro/network/api/profile/UserLoginRequestBody;",
        "Lkotlin/coroutines/jvm/internal/ContinuationImpl;",
    ),
)

/** Signing in to the app also signs the website session in, so it never asks for the password again. */
internal val sharedLoginPatch = bytecodePatch(
    name = "One sign-in for app and website",
    description = "Signing in to the app with username and password also signs in to geocaching.com, which the " +
        "website features need. The password is kept encrypted with the Android Keystore.",
) {
    compatibleWith(Constants.COMPATIBILITY)

    dependsOn(extensionHookPatch)

    execute {
        AppLoginFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p1 .. p1 }, ${Constants.GC_LIVE_SESSION}->rememberAppLogin(Ljava/lang/Object;)V",
        )
    }
}
