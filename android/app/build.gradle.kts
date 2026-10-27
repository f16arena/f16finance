plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Push через Firebase: читает app/google-services.json (в git его нет — репозиторий публичный).
    id("com.google.gms.google-services")
    // Падения у операторов — в консоль Firebase, с устройством, версией Android и стеком.
    id("com.google.firebase.crashlytics")
    // Профили запуска: Android заранее компилирует код старта и прокрутки.
    id("androidx.baselineprofile")
}

android {
    namespace = "kz.ordaops.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "kz.ordaops.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.0"
        // Как ORDA_API_BASE_URL в apple/Config.xcconfig: приложение знает только сайт.
        buildConfigField("String", "API_BASE_URL", "\"https://www.ordaops.kz\"")
    }

    // Постоянный ключ релизов: обновление APK ставится поверх, только если подпись та же.
    // Ключ и пароли — вне репозитория (~/.gradle/gradle.properties); без них подписываем отладочным.
    val releaseStore = (findProperty("ORDA_RELEASE_STORE_FILE") as String?)?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = findProperty("ORDA_RELEASE_STORE_PASSWORD") as String?
                keyAlias = findProperty("ORDA_RELEASE_KEY_ALIAS") as String?
                keyPassword = findProperty("ORDA_RELEASE_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        debug {
            // Отладочные падения в отчёты не шлём — там и так видно в Android Studio.
            manifestPlaceholders["crashlyticsEnabled"] = false
        }
        release {
            manifestPlaceholders["crashlyticsEnabled"] = true
            // Символы встроенных библиотек (камера, ML Kit) — чтобы Play и Crashlytics разбирали их падения.
            ndk { debugSymbolLevel = "FULL" }
            // R8: ужимает и оптимизирует код — Compose в релизе в разы плавнее отладочной сборки.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    // Каталог прав — тот же файл, что читает айфон (apple/Contracts): страницы, группы, модули.
    // Копии нет, поэтому каталог приложения не может разойтись с контрактом.
    sourceSets {
        getByName("main").assets.srcDir("../../apple/Contracts")
        getByName("test").resources.srcDir("../../apple/Contracts")
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
    // 1.5.0-alpha поверх BOM (в 1.4.0 expressive-API закрыты): нужны волнистый прогресс и морфящийся индикатор загрузки.
    implementation("androidx.compose.material3:material3:1.5.0-alpha04")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    // Ставит Baseline Profile при установке из APK (без Google Play).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))
    // Заставка с анимированным знаком.
    implementation("androidx.core:core-splashscreen:1.0.1")
    // Виджет «Смена» на рабочем столе и его периодическое обновление.
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")

    // Адаптивность: навигация (вкладки снизу / панель сбоку) и «список + подробности» под ширину экрана.
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite:1.5.0-alpha04")
    implementation("androidx.compose.material3.adaptive:adaptive:1.2.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.2.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.2.0")

    // Фото товаров и вложений: кэш в памяти и на диске, загрузка вне главного потока.
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    // Утечки памяти — только в отладочной сборке.
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
    implementation("androidx.navigation:navigation-compose:2.9.4")
    implementation("androidx.security:security-crypto:1.1.0")
    // Вход по отпечатку/лицу — как Face ID на айфоне.
    implementation("androidx.biometric:biometric:1.1.0")
    // Разделы кабинета, у которых ещё нет нативного экрана, открываются сайтом во вкладке Chrome.
    implementation("androidx.browser:browser:1.8.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-crashlytics")

    // Сканер штрихкодов (касса, ревизия) — камера + ML Kit, как AVFoundation на айфоне.
    implementation("androidx.camera:camera-camera2:1.5.0")
    implementation("androidx.camera:camera-lifecycle:1.5.0")
    implementation("androidx.camera:camera-view:1.5.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    testImplementation("junit:junit:4.13.2")
}
