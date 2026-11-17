// The watch app: word-synced Spicy Lyrics for whatever is playing on the paired phone.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tx24.spicyplayer.wear"
    compileSdk = 37

    defaultConfig {
        // The same application as the phone app: the Wear OS Data Layer only talks between apps
        // that share a package name and signature (see ../app/build.gradle.kts).
        applicationId = "com.tx24.spicyplayer.next"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // A made-up song for looking at the screen with no phone (see Demo.kt).
        buildConfigField("boolean", "DEMO", "false")
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "DEMO", "true")
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Spicy Lyrics Debug")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("CI") == null) signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "Spicy Lyrics")
        }
        create("profile") {
            initWith(getByName("release"))
            buildConfigField("boolean", "DEMO", "true")
            applicationIdSuffix = ".profile"
            versionNameSuffix = "-profile"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            resValue("string", "app_name", "Spicy Lyrics Profile")
        }
    }

    lint {
        // The release build's lint pass needs the lint tool's own jars; `./gradlew :wear:lintDebug`
        // runs the same checks against the debug variant, which doesn't.
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":wearproto"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.wear.compose:compose-material3:1.7.1")
    implementation("androidx.wear.compose:compose-foundation:1.7.1")
    implementation("androidx.wear.compose:compose-navigation:1.7.1")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.concurrent:concurrent-futures:1.3.0")
    implementation("com.google.guava:guava:33.5.0-android")
    implementation("androidx.wear:wear:1.4.0")
    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
