import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localProps = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProps.load(it) }
}
val mapkitApiKey: String =
    (System.getenv("MAPKIT_API_KEY") ?: localProps.getProperty("MAPKIT_API_KEY") ?: "YOUR_API_KEY")
// Метка сборки для экрана «О программе» (видно когда собрано).
val buildTime: String =
    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
// ABI для упаковки APK: задает build-apk.bat через -PtargetAbis (напр. "arm64-v8a,armeabi-v7a").
// Без свойства — все ABI из зависимостей (нужно для debug на эмуляторе x86_64).
// Это НЕ вырезание кода из проекта: MapKit остается целиком, выбирается лишь что класть в APK.
val targetAbis: String? = findProperty("targetAbis") as String?
// Единый релизный кейстор на всех машинах: иначе сборки с разных ПК подписываются разными
// debug-ключами и телефон требует сноса приложения при обновлении. Путь/пароли — только
// в local.properties (вне git). Кейстора нет (CI, свежая машина) — fallback на debug-подпись.
val releaseStoreProp = localProps.getProperty("RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val hasReleaseKeystore = releaseStoreProp != null && rootProject.file(releaseStoreProp).exists()
if (releaseStoreProp != null && !hasReleaseKeystore) {
    logger.warn("RELEASE_STORE_FILE=$releaseStoreProp, но файла нет — release подписывается debug-ключом!")
}

android {
    namespace = "ru.fogmap"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.fogmap"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-mvp"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "MAPKIT_API_KEY", "\"$mapkitApiKey\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(releaseStoreProp!!)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS") ?: "fogmap"
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // Release для живых телефонов: эмуляторные x86/x86_64 в APK не кладем.
            // Debug не трогаем — эмулятору нужен x86_64.
            if (!targetAbis.isNullOrBlank()) {
                ndk { abiFilters += targetAbis.split(",").map { it.trim() } }
            }
        }
        debug {
            applicationIdSuffix = ".dev"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // MapKit Full 4.42.0 (карта + поиск/геокодер по решению Full с запасом)
    implementation("com.yandex.android:maps.mobile:4.42.0-full")

    // Compose BOM + UI
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // Room + Coroutines/Flow
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Геолокация (Fused). Без Play Services — заглушка в UI (см. TrackingPreconditions).
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Авторестарт трекинга после ребута через expedited-Worker (задача 3.3):
    // прямой startForegroundService из BootReceiver запрещен из фона на API 31+.
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // --- Tests ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
