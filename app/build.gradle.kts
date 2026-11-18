import java.security.MessageDigest

plugins { id("com.android.application") }
// Keep this ordered input list in sync with scripts/semantic_bundle.py.
val nativeBundleDigest = MessageDigest.getInstance("SHA-256")
listOf("tools/native/runtime-lock.json", "tools/native/servers/package.json", "tools/native/servers/package-lock.json", "tools/native/servers/launch.cjs").forEach {
    nativeBundleDigest.update(rootProject.file(it).readBytes())
    nativeBundleDigest.update(0.toByte())
}
val nativeBundleId = nativeBundleDigest.digest().joinToString("") { "%02x".format(it) }
android {
    namespace = "com.forgeide.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.forgeide.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0-preview.1"
        buildConfigField("String", "NATIVE_BUNDLE_ID", "\"$nativeBundleId\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main").assets.srcDir(rootProject.file("licenses"))
    buildFeatures { aidl = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildTypes { release { isMinifyEnabled = false; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/LICENSE.txt", "META-INF/NOTICE.txt", "OSGI-INF/**") }
    lint { abortOnError = true }
    testOptions { animationsDisabled = true }
}
dependencies {
    implementation(project(":runtime-api"))
    implementation(project(":terminal-ui"))
    implementation(project(":core:editor"))
    implementation(project(":core:language"))
    implementation(project(":core:project"))
    implementation(project(":core:search"))
    implementation(project(":core:terminal"))
    implementation(project(":core:toolchain"))
    implementation(project(":core:sandbox"))
    implementation(project(":core:runtime"))
    implementation(project(":core:debug"))
    implementation(project(":core:git"))
    implementation(project(":core:settings"))
    implementation(project(":core:recovery"))
    implementation(project(":core:commands"))
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.sqlite:sqlite-bundled:2.6.2")
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.5")
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r")
    implementation("org.slf4j:slf4j-nop:1.7.36")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
