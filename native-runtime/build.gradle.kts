plugins { id("com.android.application") }
val nativeBundleId = java.security.MessageDigest.getInstance("SHA-256").digest(rootProject.file("tools/native/runtime-lock.json").readBytes()).joinToString("") { "%02x".format(it) }
android {
    namespace = "com.forgeide.runtime"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        // Equal-length relocation of Termux's /data/data/com.termux prefix is deliberate.
        applicationId = "com.forgex"
        minSdk = 26
        // Opt-in sideloaded companion only. Android's compatibility execution policy is required.
        targetSdk = 28
        versionCode = 1
        versionName = "0.2.0"
        manifestPlaceholders["forgeBundleId"] = nativeBundleId
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
        externalNativeBuild { ndkBuild { arguments += "APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true" } }
    }
    buildFeatures { aidl = true; buildConfig = true }
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    sourceSets.getByName("main").assets.srcDir(rootProject.file("build/native/assets"))
    lint { disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi"); abortOnError = true }
    androidResources { noCompress += "zip" }
    packaging { jniLibs.useLegacyPackaging = true }
}
dependencies {
    implementation(project(":runtime-api"))
    implementation(project(":core:project"))
    implementation(project(":core:toolchain"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}

tasks.named("preBuild") {
    doFirst { require(rootProject.file("build/native/assets/runtime-catalog.json").isFile) { "Missing offline native payload. Run python3 scripts/native-bundle.py build before assembling the companion." } }
}
