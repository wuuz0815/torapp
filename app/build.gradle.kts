// =============================================================================
// Datei:  app/build.gradle.kts – Build-Konfiguration der Tor-App
// Autor:  Stefan Weinzetl
// Hinweis: APP_VERSION wird ausschließlich hier gepflegt (SemVer).
// =============================================================================
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val APP_VERSION = "1.0.3"

// versionCode aus SemVer ableiten: 1.2.3 -> 10203
val versionsCode = APP_VERSION.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }

// Signaturschlüssel kommt nur aus Umgebungsvariablen (GitHub Secrets), nie aus Dateien im Projekt
val schluesselDatei: String? = System.getenv("TORAPP_KEYSTORE")

android {
    namespace = "at.weinzetl.torapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "at.weinzetl.torapp"
        minSdk = 26
        targetSdk = 35
        versionCode = versionsCode
        versionName = APP_VERSION
    }

    signingConfigs {
        create("freigabe") {
            if (schluesselDatei != null) {
                storeFile = file(schluesselDatei)
                storePassword = System.getenv("TORAPP_KEYSTORE_PASSWORT")
                keyAlias = System.getenv("TORAPP_KEY_ALIAS")
                keyPassword = System.getenv("TORAPP_KEY_PASSWORT")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (schluesselDatei != null) {
                signingConfigs.getByName("freigabe")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
