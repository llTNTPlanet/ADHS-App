plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release-Signatur: Die Release-Pipeline legt den Schlüssel aus dem Secret SIGNING_KEYSTORE_BASE64 als
// signing/release.p12 ab (nie im Repo) und übergibt das Passwort als ADHS_SIGNING_PASSWORD.
// Alle Updates müssen mit demselben Schlüssel signiert sein, sonst lehnt Android die Installation ab.
val signingPassword: String? = System.getenv("ADHS_SIGNING_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeystore = file("signing/release.p12")

// Versionsnummern setzt die Release-Pipeline (-PversionCode=… -PversionName=…).
val ciVersionCode = (findProperty("versionCode") as String?)?.toIntOrNull()
val ciVersionName = findProperty("versionName") as String?

android {
    namespace = "de.lltntplanet.adhsapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.lltntplanet.adhsapp"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "0.1.0-dev"

        // Hier sucht die App nach neuen Versionen (GitHub-Releases).
        buildConfigField("String", "UPDATE_REPO_OWNER", "\"llTNTPlanet\"")
        buildConfigField("String", "UPDATE_REPO_NAME", "\"ADHS-App\"")
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
            // Bewusst ohne R8: Ein Update darf nie an Code-Verkleinerung scheitern, sonst kommt man
            // aus einer kaputten Version nicht mehr per Selbst-Update heraus.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

// Screenshots der Oberfläche (Robolectric): ./gradlew testDebugUnitTest -Pscreenshots=true --tests "*ScreenshotTest*"
tasks.withType<Test>().configureEach {
    systemProperty("screenshots", project.findProperty("screenshots") ?: "false")
    systemProperty("screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.health.connect.client)

    implementation(libs.play.services.auth)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
