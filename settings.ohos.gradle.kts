// Sondo（Kuikly 版）鸿蒙构建入口。
//
// 鸿蒙产物必须用 Kuikly 定制的 Kotlin/Native 工具链（KBA）编译，与 Android / iOS 使用的官方 Kotlin
// 版本不同，所以单独一套 settings / build 脚本，通过 `-c` 指定：
//
//   ./gradlew -c settings.ohos.gradle.kts :shared:linkSharedReleaseSharedOhosArm64
//
// 日常直接用根目录的 ./ohos_build.sh（编译 + 拷贝 so / 头文件 / 资源到 ohosApp）。
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        // Kotlin KBA 工具链（kotlin-gradle-plugin 2.0.21-KBA-010）与 Kuikly -ohos 产物
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/") }
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/gradle-plugins/") }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/") }
    }
}

rootProject.name = "sondo"

val ohosBuildFileName = "build.ohos.gradle.kts"
rootProject.buildFileName = ohosBuildFileName

include(":shared")
project(":shared").buildFileName = ohosBuildFileName
