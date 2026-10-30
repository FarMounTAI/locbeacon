plugins {
    id("com.android.application")
    // AGP 9 内置 Kotlin，不再需要 org.jetbrains.kotlin.android
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yuanshan.locbeacon"

    // AGP 9 的块式 DSL，不再是 compileSdk = 37
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.yuanshan.locbeacon"
        // minSdk 35 (Android 15)：设备必须跑 Android 15 及以上才能安装。
        // 好处是 miuix-blur / squircle 的 RuntimeShader 路径恒可用，不用做能力探测
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 注意：内置 Kotlin 后没有 kotlin { compilerOptions { } } 这个扩展了，
// Kotlin 的 jvmTarget 由上面的 compileOptions 自动对齐。

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Miuix：小米 HyperOS 设计语言的 Compose 组件库
    // 不引 compose-bom，让 Miuix 的传递依赖决定 Compose 版本，避免和它编译时用的版本打架
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.google.android.gms:play-services-location:21.4.0")
}
