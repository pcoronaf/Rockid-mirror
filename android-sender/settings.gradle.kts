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
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
rootProject.name = "rokid-mirror-sender"

// Shared wire protocol / crypto library (also used by the receiver and the desktop tools).
includeBuild("../protocol/kotlin")

include(":app", ":capture", ":encoder", ":transport", ":discovery", ":control", ":telemetry")
