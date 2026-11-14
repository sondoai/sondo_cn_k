import java.io.ByteArrayOutputStream

plugins {
    kotlin("multiplatform")
    kotlin("native.cocoapods")
    id("com.android.library")
    id("com.google.devtools.ksp")
}

val kuiklyVersion = "${property("KUIKLY_VERSION")}-${property("KUIKLY_KOTLIN_VERSION")}"

/** 本机是否具备 iOS 编译环境（完整 Xcode）。没有时跳过 iOS target，Android 仍可独立编译。 */
val hasXcode: Boolean by lazy {
    if (!System.getProperty("os.name").lowercase().contains("mac")) return@lazy false
    try {
        val out = ByteArrayOutputStream()
        val result = exec {
            commandLine("xcodebuild", "-version")
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        result.exitValue == 0
    } catch (e: Exception) {
        false
    }
}
val enableIos = (findProperty("sondo.enableIos") as String?)?.toBoolean() ?: hasXcode
// 开发热重载才启用 Kotlin/JS；正常 Android / iOS 构建不解析 JS 工具链。
val enableHotReload = (findProperty("sondo.hotReload") as String?)?.toBoolean() ?: false

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions { jvmTarget = "17" }
        }
    }

    if (enableHotReload) {
        js(IR) {
            moduleName = "sondo"
            browser {
                webpackTask { outputFileName = "sondo.js" }
                commonWebpackConfig {
                    output?.library = "SondoHotReload"
                    output?.libraryTarget = "var"
                    devtool = "source-map"
                }
            }
            binaries.executable()
        }
    }

    if (enableIos) {
        iosX64()
        iosArm64()
        iosSimulatorArm64()
    }

    cocoapods {
        summary = "Sondo Kuikly shared business module"
        homepage = "https://sondo.vip"
        version = "1.0"
        ios.deploymentTarget = "14.1"
        podfile = project.file("../iosApp/Podfile")
        framework {
            baseName = "shared"
            isStatic = true
            license = "MIT"
        }
        extraSpecAttributes["resources"] = "['src/commonMain/assets/**']"
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
        }
        val commonMain by getting {
            dependencies {
                implementation("com.tencent.kuikly-open:core:$kuiklyVersion")
                implementation("com.tencent.kuikly-open:core-annotations:$kuiklyVersion")
            }
        }
        val androidMain by getting {
            dependencies {
                api("com.tencent.kuikly-open:core-render-android:$kuiklyVersion")
            }
        }
    }
}

dependencies {
    val kspDep = "com.tencent.kuikly-open:core-ksp:$kuiklyVersion"
    add("kspAndroid", kspDep)
    if (enableHotReload) {
        add("kspJs", kspDep)
    }
    if (enableIos) {
        add("kspIosArm64", kspDep)
        add("kspIosX64", kspDep)
        add("kspIosSimulatorArm64", kspDep)
    }
}

android {
    namespace = "com.sondo.app.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 27
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 公共图片 / 字体 / lottie 资源：三端共用 commonMain/assets
    sourceSets["main"].assets.srcDir("src/commonMain/assets")
}

ksp {
    // 指定 pageName 时只打包单页（动态化场景），默认全量
    arg("pageName", (findProperty("pageName") as String?) ?: "")
}
