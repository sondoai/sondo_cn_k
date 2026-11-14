// Sondo（Kuikly 版）根构建脚本 —— 鸿蒙（Kotlin KBA 工具链）。
// 仅在 `-c settings.ohos.gradle.kts` 时生效；Android / iOS 用 build.gradle.kts。
plugins {
    kotlin("multiplatform") version "2.0.21-KBA-010" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}
