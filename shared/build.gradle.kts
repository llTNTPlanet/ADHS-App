plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Gemeinsames Datenformat von Handy-App (:app) und Uhr-App (:wear): Pfade, Nachrichten und die Übersicht
// für die Uhr. Reines Kotlin ohne Android-Abhängigkeiten, damit beide Seiten garantiert dasselbe sprechen.
android {
    namespace = "de.lltntplanet.adhsapp.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
