plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.jwise.rhubarb"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.jwise.rhubarb"
        minSdk = 28
        targetSdk = 37
        versionCode = 35
        versionName = "0.15.1"
        // Ship librexterm's symbol table inside the bundle so Play can name native frames.
        ndk { debugSymbolLevel = "SYMBOL_TABLE" }
    }
    // The NDK that strips librexterm.so when packaging; same one scripts/build-native.sh uses.
    ndkVersion = "30.0.16248370"

    // The Play upload key lives outside the repo. Its location and password come from
    // ~/.gradle/gradle.properties (RHUBARB_UPLOAD_STORE_FILE, RHUBARB_UPLOAD_PASSWORD);
    // without them, release builds fall back to the debug key so anyone can still build.
    val uploadStore = providers.gradleProperty("RHUBARB_UPLOAD_STORE_FILE").orNull
        ?.let { file(it.replaceFirst("~", System.getProperty("user.home"))) }
        ?.takeIf { it.exists() }
    val uploadPassword = providers.gradleProperty("RHUBARB_UPLOAD_PASSWORD").orNull

    signingConfigs {
        if (uploadStore != null && uploadPassword != null) {
            create("upload") {
                storeFile = uploadStore
                storePassword = uploadPassword
                keyAlias = providers.gradleProperty("RHUBARB_UPLOAD_KEY_ALIAS").orNull ?: "upload"
                keyPassword = uploadPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    // M3 Expressive components (LoadingIndicator, MaterialShapes, ButtonGroup, toolbars…) ship in 1.5.
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.5")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // QR scanning for server addresses: ZXing, fully on-device (no Google Play services or analytics).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
