// Sondo（Kuikly 版）根构建脚本 —— Android / iOS。
// 鸿蒙使用 settings.ohos.gradle.kts + build.ohos.gradle.kts（Kotlin KBA 工具链），见 README。
plugins {
    kotlin("multiplatform") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}
