import java.util.Properties

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

    // 正式签名：极光一键登录、微信登录 / 支付都按「包名 + 签名」校验，debug 签名在真机上取号会失败、
    // 授权页不出现（直接落到短信登录页）。和参考工程一样，debug / release 都用正式签名。
    // 需要 key/key.jks 与 key/keystore.properties（storePassword / keyAlias / keyPassword），缺任一项时退回 debug 签名。
    val releaseKeystore = file("key/key.jks")
    val keystorePropsFile = file("key/keystore.properties")
    val releaseSigning = if (releaseKeystore.exists() && keystorePropsFile.exists()) {
        val props = Properties().apply { keystorePropsFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = releaseKeystore
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
            enableV1Signing = true
            enableV2Signing = true
        }
    } else {
        logger.warn("androidApp: key/key.jks 或 key/keystore.properties 缺失，使用 debug 签名（真机一键登录 / 微信不可用）")
        signingConfigs.getByName("debug")
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = releaseSigning
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning
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
