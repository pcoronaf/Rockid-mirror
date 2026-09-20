plugins { alias(libs.plugins.kotlin.jvm) apply false }

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "application")
    repositories { mavenCentral() }
    dependencies {
        "implementation"("com.rokidmirror:mirror-protocol")
        "testImplementation"(rootProject.libs.junit)
    }
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
    tasks.withType<Test>().configureEach { useJUnit() }
}
