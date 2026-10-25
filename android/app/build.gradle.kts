plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "kz.ordaops.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "kz.ordaops.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Как ORDA_API_BASE_URL в apple/Config.xcconfig: приложение знает только сайт.
        buildConfigField("String", "API_BASE_URL", "\"https://www.ordaops.kz\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Пока раздаём APK вручную — подписываем отладочным ключом,
            // чтобы файл ставился без отдельного keystore.
            signingConfig = signingConfigs.getByName("debug")
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.navigation:navigation-compose:2.9.4")
    implementation("androidx.security:security-crypto:1.1.0")
    // Вход по отпечатку/лицу — как Face ID на айфоне.
    implementation("androidx.biometric:biometric:1.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Сканер штрихкодов (касса, ревизия) — камера + ML Kit, как AVFoundation на айфоне.
    implementation("androidx.camera:camera-camera2:1.5.0")
    implementation("androidx.camera:camera-lifecycle:1.5.0")
    implementation("androidx.camera:camera-view:1.5.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
