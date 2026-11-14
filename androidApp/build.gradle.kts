plugins {
    id("com.android.application")
    kotlin("android")
}

val kuiklyVersion = "${property("KUIKLY_VERSION")}-${property("KUIKLY_KOTLIN_VERSION")}"

android {
    namespace = "com.sondo.music.video.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sondo.music.video.ai"
        minSdk = 27
        targetSdk = 34
        versionCode = (property("APP_VERSION_CODE_ANDROID") as String).toInt()
        versionName = property("APP_VERSION_NAME_ANDROID") as String

        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        buildConfigField("String", "API_ENV", "\"${property("API_ENV")}\"")
        buildConfigField("boolean", "SHOW_TOKEN_EDITOR", "${property("SHOW_TOKEN_EDITOR")}")

        // 极光（推送 + 一键登录）。极光认证 Android 只读清单里的 AppKey，不读运行时传入的值；
        // 必须与 shared 的 AppConstants.jpushAppKey / jverifyAppKey 保持一致。
        manifestPlaceholders["JPUSH_PKGNAME"] = "com.sondo.music.video.ai"
        manifestPlaceholders["JPUSH_APPKEY"] = "19c4451c32f15deb993e6c7a"
        manifestPlaceholders["JPUSH_CHANNEL"] = "developer-default"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 发布签名在 key/ 目录配置好后替换；默认用 debug 签名保证可出包自测
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs { keepDebugSymbols += "**/*.so" }
        resources { excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1") }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("com.tencent.kuikly-open:core:$kuiklyVersion")
    implementation("com.tencent.kuikly-open:core-render-android:$kuiklyVersion")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // 图片 / 网络 / 播放
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.2.1")
    // 媒体模块：Lottie 动画视图、图片 EXIF 方向、Activity Result（相册 / 相机 / 文件选择）
    implementation("com.airbnb.android:lottie:6.4.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // ---- 三方 SDK（版本与 Flutter 参考工程一致）----
    // 支付宝 App 支付（积分包）
    implementation("com.alipay.sdk:alipaysdk-android:15.8.42")
    // 微信登录（仅授权，无支付 / 分享）
    implementation("com.tencent.mm.opensdk:wechat-sdk-android:6.8.34")
    // 极光推送 + 极光认证。两者都依赖 jcore [1.0.0,)，这里钉成一个确定版本，避免随构建时间漂移。
    implementation("cn.jiguang.sdk:jpush:6.2.1")
    implementation("cn.jiguang.sdk:jverification:3.4.8")
    implementation("cn.jiguang.sdk:jcore") { version { strictly("5.6.0") } }
    // 友盟统计
    implementation("com.umeng.umsdk:common:10.0.1")
    implementation("com.umeng.umsdk:asms:1.8.7.2")
}
