// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    extra.apply {
        set("versionCompiler", 36)
        set("versionTarget", 36)
        set("minSdkVersion", 26)
        set("versionCode", 1)
        set("versionNameString", "1.0")
        set("javaSourceCompatibility", JavaVersion.VERSION_11)
        set("javaTargetCompatibility", JavaVersion.VERSION_11)
        set("androidXVersion", "1.7.0")
        set("materialVersion", "1.12.0")
        set("constraintlayoutVersion", "2.1.4")
        set("versionBuildTool", "34.0.0")
        set("ndkVersion", "27.0.12077973")

        // Добавляем недостающую переменную для libuvccommon
        set("STL_NAME", "c++_static")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}