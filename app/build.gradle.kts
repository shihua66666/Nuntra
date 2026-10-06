import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.shihua66666.nuntra"
    // 36 是 AGP 8.13.2 支持的上限；依赖已按此核对过 minCompileSdk
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shihua66666.nuntra"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 只打包需要的语言资源（中文 + 英文），减小 APK。
        // 注意：resourceConfigurations 在新版 AGP 已废弃（CI 里能看到该警告），
        // 但替代 API androidResources.localeFilters 在 AGP 8.13 上是否可用尚未确认，
        // 而 AGP 9 又会移除本属性 —— 因此这里先移除该优化：
        // 全语言资源的 APK 体积代价很小，但换来的是在 AGP 8/9 上都能编译。
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 本项目 minSdk 29，无 desugar 需求
        isCoreLibraryDesugaringEnabled = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // 刻意不设置 -Xjvm-default：
        //  · 它在 Kotlin 2.2.0 起被标记弃用（CI 里会报 DeprecatedJvmDefaultFlag 之类警告）；
        //  · 它只影响「Kotlin 接口默认方法」如何生成 Java 兼容的 DefaultImpls；
        //  · 本工程没有任何 Java 源码，接口也都不含默认实现，因此该参数零作用。
        //    移除它可消除警告，且不改变任何编译产物的行为。
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.ext)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
