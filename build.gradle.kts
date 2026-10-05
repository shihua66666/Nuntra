// Nuntra / 通知中继终端 —— 根构建脚本
// 只声明插件，不在根工程应用；具体配置在 app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
