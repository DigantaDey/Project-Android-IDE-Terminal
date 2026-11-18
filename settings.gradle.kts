pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ForgeIDE"

include(":app")

// Pure-JVM core modules. Every IDE capability that does not strictly require the
// Android framework lives here so it can be unit-tested on the JVM and reused
// from any host (app, desktop harness, CI).
include(":core:util")
include(":core:editor")
include(":core:language")
include(":core:project")
include(":core:search")
include(":core:terminal")
include(":core:toolchain")
include(":core:sandbox")
include(":core:git")
include(":core:debug")
include(":core:settings")
include(":core:recovery")
include(":core:commands")
include(":core:runtime")

include(":runtime-api", ":native-runtime", ":terminal-ui")
