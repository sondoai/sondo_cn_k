pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/") }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://mirrors.tencent.com/nexus/repository/maven-tencent/") }
        // 极光（JPush / JVerify）
        maven { url = uri("https://developer.huawei.com/repo/") }
    }
}

rootProject.name = "sondo"

include(":shared")
include(":androidApp")
