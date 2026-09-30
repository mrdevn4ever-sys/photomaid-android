plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

// Konfiguration über Gradle-Properties (z. B. ~/.gradle/gradle.properties oder -P…).
// Ohne Werte: ehrlich „Käufe sind in dieser Version noch nicht verfügbar“ (wie iOS ohne RevenueCat-Key).
fun prop(name: String): String = (project.findProperty(name) as String?) ?: ""

android {
    namespace = "app.photomaid"
    compileSdk = 37

    defaultConfig {
        // PLATZHALTER (Release-Blocker): finale Application-ID legt der Inhaber fest.
        applicationId = "app.photomaid.android"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "PLAY_PRO_PRODUCT_ID", "\"${prop("photomaid.playProProductId")}\"")
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"${prop("photomaid.privacyPolicyUrl")}\"")
        buildConfigField("boolean", "QA_FIXTURES", "false")
        // Werbung (AdMob): echte IDs nur per Gradle-Property, nie im Repository. Leer = keine Werbung (Release).
        manifestPlaceholders["admobAppId"] = prop("photomaid.admobAppId")
        buildConfigField("String", "ADMOB_APP_ID", "\"${prop("photomaid.admobAppId")}\"")
        buildConfigField("String", "ADMOB_NATIVE_UNIT", "\"${prop("photomaid.admobNativeUnit")}\"")
        buildConfigField("String", "ADMOB_REWARDED_UNIT", "\"${prop("photomaid.admobRewardedUnit")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signatur: Release-Blocker (kein Upload-Keystore im Repository). Lokal mit Debug-Key prüfbar:
            signingConfig = if (prop("photomaid.signWithDebug") == "true") signingConfigs.getByName("debug") else null
        }
        // Debug: offizielle Google-Test-Anzeigen (nie Produktions-IDs, keine echten Klicks).
        getByName("debug") {
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "ADMOB_APP_ID", "\"ca-app-pub-3940256099942544~3347511713\"")
            buildConfigField("String", "ADMOB_NATIVE_UNIT", "\"ca-app-pub-3940256099942544/2247696110\"")
            buildConfigField("String", "ADMOB_REWARDED_UNIT", "\"ca-app-pub-3940256099942544/5224354917\"")
        }
        // QA-Build: synthetische Fixtures (nur mit Intent-Extra „qa-fixtures“), niemals Release.
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            buildConfigField("boolean", "QA_FIXTURES", "true")
            matchingFallbacks += listOf("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("robolectric.pixelCopyRenderMode", "hardware") }
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = true
    }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { jvmToolchain(21) }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.documentfile)
    // Activity-Result-API: Lint verlangt Fragment >= 1.3 (transitiv sonst veraltet).
    implementation(libs.androidx.fragment)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.billing)
    implementation(libs.play.ads)
    implementation(libs.ump)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit)
    debugImplementation(libs.compose.ui.test.manifest)
    "qaImplementation"(libs.compose.ui.test.manifest)
}
