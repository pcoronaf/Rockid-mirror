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
        // Kadb's SPAKE2 pairing dependency is published only on JitPack. Scope JitPack to that
        // one group so nothing else is pulled through it.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.Flyfish233") }
        }
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
rootProject.name = "rokid-mirror-sender"

// Shared wire protocol / crypto library (also used by the receiver and the desktop tools).
includeBuild("../protocol/kotlin")

include(":app", ":capture", ":encoder", ":transport", ":discovery", ":control", ":telemetry")
