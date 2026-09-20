pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
dependencyResolutionManagement {
    repositories { mavenCentral(); google() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "rokid-mirror-tools"
includeBuild("../protocol/kotlin")
include(":mock-receiver", ":stream-generator", ":packet-inspector")
