// 车机本地视频播放器 —— 应用模块（普通 Activity 架构，可运行于 Android 11 车机 / AAOS）。
// 说明：AGP 9 内置 Kotlin 支持，无需应用 Kotlin 插件；本模块不使用 Compose。
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.aiocw.carvideo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.aiocw.carvideo"
        minSdk = 30          // 目标车机 Android 11（API 30）
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
