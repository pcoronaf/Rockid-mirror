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
        // Rokid's Glasses SDK (com.rokid.security:glass3.open.sdk) lives at
        // https://maven.rokid.com/repository/maven-public/ . It is NOT a dependency of the
        // receiver: the video path uses only standard Android APIs. The repository is listed so
        // a future RokidSdkPlatformAdapter (touch bar / IMU) can be added without touching
        // settings. See docs/rokid-sdk-findings.md.
        maven {
            url = uri("https://maven.rokid.com/repository/maven-public/")
            content { includeGroup("com.rokid.security") }
        }
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
rootProject.name = "rokid-mirror-receiver"

includeBuild("../protocol/kotlin")

include(":app", ":platform", ":decoder", ":transport", ":renderer", ":control", ":telemetry", ":camera")
