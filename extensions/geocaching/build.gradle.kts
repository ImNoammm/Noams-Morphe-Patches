extension {
    name = "extensions/geocaching.mpe"
}

android {
    namespace = "app.noam.extension.geocaching"
}

dependencies {
    // Provided by the target app at runtime, never bundled into the patched APK.
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.annotation)
}
