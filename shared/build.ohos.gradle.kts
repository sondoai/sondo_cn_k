// shared 模块的鸿蒙构建脚本：把 src/commonMain 编译成 libshared.so（Kotlin/Native, ohosArm64）。
// 与 build.gradle.kts 共用同一份源码（src/commonMain），只是 target 与工具链不同。
plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp")
}

// 鸿蒙产物带 -ohos 后缀，例如 2.30.0-2.0.21-ohos
val kuiklyOhosVersion = "${property("KUIKLY_VERSION")}-${property("KUIKLY_KOTLIN_VERSION")}-ohos"

kotlin {
    ohosArm64 {
        // 产物：build/bin/ohosArm64/shared{Debug,Release}Shared/libshared.so + libshared_api.h
        // 任务：linkShared{Debug,Release}SharedOhosArm64
        binaries.sharedLib("shared") {
            // 保留轻量调试信息，崩溃堆栈可读
            freeCompilerArgs += "-Xadd-light-debug=enable"
            linkerOpts += "--build-id=sha1"
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
        }
        val commonMain by getting {
            dependencies {
                implementation("com.tencent.kuikly-open:core:$kuiklyOhosVersion")
                implementation("com.tencent.kuikly-open:core-annotations:$kuiklyOhosVersion")
            }
        }
    }
}

dependencies {
    // 生成 KuiklyCoreEntry（页面路由表 + C 入口 initKuikly）
    add("kspOhosArm64", "com.tencent.kuikly-open:core-ksp:$kuiklyOhosVersion")
}

ksp {
    // 与 build.gradle.kts 保持一致：指定 pageName 时只打包单页，默认全量
    arg("pageName", (findProperty("pageName") as String?) ?: "")
}
