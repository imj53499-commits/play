plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.play.mcserver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.play.mcserver"
        minSdk = 26
        // 28 이하여야 앱 내부 저장소에 풀어둔 java 실행파일을 직접 실행할 수 있음 (Termux와 같은 방식)
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    androidResources {
        noCompress += listOf("zip")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
