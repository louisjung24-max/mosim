import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 서명 정보는 저장소에 절대 넣지 않습니다. CI 또는 로컬의 keystore.properties(.gitignore)에서만 읽습니다.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.containsKey("storeFile") && rootProject.file(keystoreProps["storeFile"] as String).exists()

android {
    namespace = "com.hiddenframe.mosim"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hiddenframe.mosim"
        minSdk = 28
        targetSdk = 33
        versionCode = 2
        versionName = "0.2.0"
        // 알림 서버 주소. 비어 있으면 서버 전송을 건너뛰고 화면에만 표시합니다.
        buildConfigField("String", "ALERT_ENDPOINT", "\"${System.getenv("MOSIM_ALERT_ENDPOINT") ?: ""}\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 릴리스 키가 없으면 디버그 키로 서명해 심사용 설치가 가능한 APK를 만듭니다.
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Android Automotive OS 차량 속성(android.car). Pleos 차량 서비스가 없는 일반 AAOS 에뮬레이터용 대체 경로입니다.
    useLibrary("android.car")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Pleos Connect SDK
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
}
