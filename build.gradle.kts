import java.util.Properties

plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    id("com.google.gms.google-services") version "4.6.0" apply false
}

// The Firebase project that wakes the sharing phone (see docs/PLAY.md). Without its
// google-services.json the app still builds; Get OTP then can't ring the other phone.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "co.atomiclabs.otpair"
    compileSdk = 36
    defaultConfig {
        applicationId = "co.atomiclabs.otpair"
        minSdk = 26
        // This build never reads text messages itself (Android's one-message consent pop-up
        // does), so Android 17's late delivery of OTP texts to SMS-reading apps doesn't apply.
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
    implementation("com.google.android.gms:play-services-auth-api-phone:18.3.1") // "Allow OTP Please to read this message?"
    implementation("com.google.firebase:firebase-messaging:26.0.2") // wakes the sharing phone when asked
    implementation("androidx.fragment:fragment:1.8.9") // Play services brings 1.0, which breaks activity results
    testImplementation("junit:junit:4.13.2")
}
