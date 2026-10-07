import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ══════════════════════════════════════════════════════════════════
// 版本号：随 git 提交数自动递增
//
// 为什么用 git 提交数：
//   versionCode 必须**严格递增**，否则 Android 会拒绝覆盖安装
//   （versionCode 回退 + 签名不同 = 必须先卸载 = DataStore 数据全丢）。
//   手动改容易忘，git 提交数则是「每次推送代码就 +1」的天然单调量。
//
// ⚠ CI 必须用 actions/checkout 的 fetch-depth: 0 取全量历史，
//   浅克隆（默认 depth=1）下 rev-list --count HEAD 永远是 1，版本号就卡死了。
val gitCommitCount: Int = runCatching {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
    process.waitFor()
    text.toInt()
}.getOrDefault(0)

/** versionCode 基数：留出低段空间，便于日后手工微调。 */
val versionCodeBase = 10_000
val computedVersionCode = versionCodeBase + gitCommitCount
val computedVersionName = "1.0." + gitCommitCount

// ══════════════════════════════════════════════════════════════════
// 固定签名
// ══════════════════════════════════════════════════════════════════
//
// 问题：AGP 的默认 debug 签名用 ~/.android/debug.keystore，
//   而 GitHub Actions 每次都是**全新运行器** → 每次自动生成一个新的 debug 密钥
//   → 每次 APK 签名都不同 → Android 拒绝覆盖安装
//   （INSTALL_FAILED_UPDATE_INCOMPATIBLE）→ 必须先卸载 → 标签/关注人/主题全丢。
//
// 解决：把签名固定下来。取值优先级：
//   1) 环境变量 KEYSTORE_PATH（CI 里由 Secrets 解码后指定）
//   2) 仓库内 app/keystore/debug.keystore（debug 专用库，密码是公开约定值，可入库）
//   3) keystore.properties 里的 storeFile（本地使用，不入库）
val debugKeystorePassword = "android"
val debugKeystoreAlias = "androiddebugkey"

// 刻意不用 build script 里的 local fun / run+return@run：
// 那些写法在 Kotlin DSL 脚本里虽然合法，但会让配置期出错的排查成本变高。
// 这里全部写成最朴素的 val + 表达式，配置期行为一目了然。
val keystoreProps = java.util.Properties()
runCatching {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { keystoreProps.load(it) }
}.onFailure { println("[nuntra] 读取 keystore.properties 失败：" + it.message) }

/** 取签名参数：环境变量优先（CI），其次 keystore.properties（本地）。 */
val signingValue: (String, String) -> String? = { propKey, envKey ->
    val raw = System.getenv(envKey) ?: keystoreProps.getProperty(propKey)
    if (raw.isNullOrBlank()) null else raw
}

val envKeystorePath = System.getenv("KEYSTORE_PATH")
val repoKeystore = rootProject.file("app/keystore/debug.keystore")
val propKeystore = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }

val fixedKeystoreFile: java.io.File? = when {
    !envKeystorePath.isNullOrBlank() && java.io.File(envKeystorePath).exists() ->
        java.io.File(envKeystorePath)
    repoKeystore.exists() -> repoKeystore
    propKeystore != null && propKeystore.exists() -> propKeystore
    else -> null
}
val hasFixedSigning = fixedKeystoreFile != null

println("[nuntra] versionCode=$computedVersionCode versionName=$computedVersionName gitCommits=$gitCommitCount")
println(
    "[nuntra] 固定签名=" + (fixedKeystoreFile?.absolutePath
        ?: "未配置 → 将使用运行器临时 debug 密钥（APK 无法覆盖安装，会丢数据！）"),
)

android {
    namespace = "com.shihua66666.nuntra"
    // 36 是 AGP 8.13.2 支持的上限；依赖已按此核对过 minCompileSdk
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shihua66666.nuntra"
        minSdk = 29
        targetSdk = 34
        versionCode = computedVersionCode
        versionName = computedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 只打包需要的语言资源（中文 + 英文），减小 APK。
        // 注意：resourceConfigurations 在新版 AGP 已废弃（CI 里能看到该警告），
        // 但替代 API androidResources.localeFilters 在 AGP 8.13 上是否可用尚未确认，
        // 而 AGP 9 又会移除本属性 —— 因此这里先移除该优化：
        // 全语言资源的 APK 体积代价很小，但换来的是在 AGP 8/9 上都能编译。
    }

    // 固定签名配置：只有拿到密钥库时才创建，
    // 否则 AGP 会在配置阶段因为 storeFile 不存在而直接失败。
    signingConfigs {
        if (hasFixedSigning) {
            create("fixed") {
                storeFile = fixedKeystoreFile
                // 密码优先级：环境变量 → keystore.properties → debug 公开约定值
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                    ?: debugKeystorePassword
                keyAlias = signingValue("keyAlias", "KEY_ALIAS") ?: debugKeystoreAlias
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
                    ?: debugKeystorePassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // ★★ 关键：debug 也用固定签名 ★★
            //   不固定的话 CI 每次都会生成新密钥，APK 无法覆盖安装。
            // findByName 兜底：万一定名配置因某种原因没创建，也退回默认 debug 签名，
            // 绝不让「签名配置缺失」变成整个构建失败。
            signingConfig = signingConfigs.findByName("fixed")
                ?: signingConfigs.getByName("debug")
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
