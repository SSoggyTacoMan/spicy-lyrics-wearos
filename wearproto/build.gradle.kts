// Plain JVM Kotlin shared by the phone app and the watch app: what travels over the Wear OS Data
// Layer, how it's packed, and the timeline the watch draws from it. No Android SDK, so it's
// tested with `./gradlew :wearproto:test`.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_11
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
