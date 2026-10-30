buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 内置 Kotlin，默认自带 KGP 2.2.10。
        // 这里显式拉到 2.4.21，和 compose 编译器插件保持同一版本，避免两者打架。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
    // 注意：不能再应用 org.jetbrains.kotlin.android，AGP 9 会直接拒绝
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
