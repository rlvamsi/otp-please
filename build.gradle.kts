import java.util.Properties

plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
}

android {
    namespace = "co.atomiclabs.otpair"
    compileSdk = 36
    defaultConfig {
        applicationId = "co.atomiclabs.otpair"
        minSdk = 26
        // Do not raise to 37+: on Android 17, apps targeting API 37 or higher receive
        // OTP text messages three hours late, which would defeat the whole app.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    buildFeatures { compose = true }

    // A published APK must be signed with a real key: see "Publishing a build" in the README.
    // keystore.properties and the keystore itself stay out of git. Without them, `assembleRelease`
    // falls back to this computer's debug key, which is fine for your own two phones only.
    val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") // QR: draw and scan
    testImplementation("junit:junit:4.13.2")
}
