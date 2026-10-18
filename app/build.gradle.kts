import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.mebox.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.mebox.android"
        minSdk = 26
        targetSdk = 36
        // CI 通过 -PmeboxVersionCode=<run_number> 递增 versionCode；本地默认 1。
        versionCode = providers.gradleProperty("meboxVersionCode").orNull?.toIntOrNull() ?: 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 只有 CI/本地显式提供 keystore 时才创建发布签名配置；密钥不进入仓库。
        val keystoreFile = System.getenv("MEBOX_KEYSTORE_FILE")
        if (!keystoreFile.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("MEBOX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MEBOX_KEY_ALIAS")
                keyPassword = System.getenv("MEBOX_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    /*
     * 电视盒子、手机和模拟器的 CPU 架构并不统一：
     * - arm64-v8a / armeabi-v7a：绝大多数手机与电视盒子；
     * - x86 / x86_64：Android TV 模拟器与少数 Intel 盒子。
     *
     * LibVLC 的 AAR 自带这四套原生库，因此按 ABI 拆分，避免每个用户都下载
     * 四个架构的 so。同时保留 universal APK，方便侧载和排查问题。
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 供 CI 读取版本号生成 Release 标签与文件名。
tasks.register("printVersionName") {
    doLast {
        println(android.defaultConfig.versionName ?: "unknown")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    // 播放器数据源复用 App 的 OkHttp 连接池：起播前 API 已经建好的连接
    // 可以直接复用，省掉一次 DNS+TCP+TLS（跨境链路上常是数秒）。
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    // 电视端扫码输入：只引入纯 Java 的二维码编码器，不包含相机/扫码 UI。
    implementation(libs.zxing.core)

    // 第三方播放内核：LibVLC 自带 FFmpeg，用于安卓系统解码器不支持的编码/容器。
    implementation(libs.libvlc)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
