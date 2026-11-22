plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Uhr-App (Wear OS) zur ADHS App. Sie spricht mit der Handy-App über den Wearable Data Layer – das geht nur,
// wenn beide Apps dieselbe applicationId haben UND mit demselben Schlüssel signiert sind. Deshalb nutzt die
// Uhr-App denselben Release-Schlüssel wie die Handy-App (app/signing/release.p12, in der CI aus den Secrets).
val signingPassword: String? = System.getenv("ADHS_SIGNING_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeystore = rootProject.file("app/signing/release.p12")

// Versionsnummern setzt die Release-Pipeline (-PversionCode=… -PversionName=…) – dieselben wie beim Handy.
val ciVersionCode = (findProperty("versionCode") as String?)?.toIntOrNull()
val ciVersionName = findProperty("versionName") as String?

android {
    namespace = "de.lltntplanet.adhsapp.wear"
    compileSdk = 36

    defaultConfig {
        // Muss mit der Handy-App übereinstimmen, sonst finden sich die beiden nicht.
        applicationId = "de.lltntplanet.adhsapp"
        // Ab Wear OS 5 (Android 14): spart Sonderwege für ältere Uhren. Die Pixel Watch 4 läuft mit Wear OS 6
        // (Android 16).
        minSdk = 34
        targetSdk = 36
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "0.1.0-dev"
    }

    signingConfigs {
        if (signingPassword != null && releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storeType = "pkcs12"
                storePassword = signingPassword
                keyAlias = "adhs"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        release {
            // Wie bei der Handy-App bewusst ohne R8.
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Gemeinsames Datenformat mit der Handy-App
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Gleiche Navigation-Version wie die Handy-App (sonst käme über Wear Compose eine alte 2.6 herein)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    // Nur die Grundsymbole; material-icons-extended wäre ohne R8 viel zu groß für die Uhr
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Compose für Wear OS (Material 3)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)

    // Verbindung zur Handy-App
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
