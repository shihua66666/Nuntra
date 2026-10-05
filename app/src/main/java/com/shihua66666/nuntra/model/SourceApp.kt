package com.shihua66666.nuntra.model

/**
 * 消息来源。
 *
 * 注意：这里**不含颜色**。颜色属于 UI 语义，统一由 ui/theme/SourceColors.kt 提供，
 * 避免 model 层依赖 Compose。
 *
 * 覆盖的腾讯系办公全家桶：微信、QQ（含 QQ 轻聊版 mobileqqi）、企业微信、TIM。
 * 企业微信与 TIM 默认不监控（通知量大），由设置页的「监控范围」分闸控制。
 */
enum class SourceApp(val displayName: String) {
    WECHAT("微信"),
    QQ("QQ"),
    WECOM("企业微信"),
    TIM("TIM"),
    SMS("短信"),
    UNKNOWN("其他");

    companion object {

        val WECHAT_PACKAGES: Set<String> = setOf("com.tencent.mm")

        val QQ_PACKAGES: Set<String> = setOf("com.tencent.mobileqq", "com.tencent.mobileqqi")

        val WECOM_PACKAGES: Set<String> = setOf("com.tencent.wework")

        val TIM_PACKAGES: Set<String> = setOf("com.tencent.tim")

        /** 全部腾讯系包名。 */
        val TENCENT_PACKAGES: Set<String> =
            WECHAT_PACKAGES + QQ_PACKAGES + WECOM_PACKAGES + TIM_PACKAGES

        /** 已知短信 App 包名，用于取不到默认短信 App 时的兜底识别。 */
        val KNOWN_SMS_PACKAGES: Set<String> = setOf(
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.miui.smsextra",
            "com.android.messaging",
            "com.coloros.mms",
            "com.oneplus.mms",
            "com.hihonor.mms",
            "com.huawei.message",
            "com.meizu.flyme.message",
            "com.vivo.message",
            "com.bbk.message",
        )

        /**
         * 按包名判定来源。
         *
         * @param smsPackages 由 SmsAppResolver 解析出的本机短信 App 包名集合。
         */
        fun fromPackage(packageName: String, smsPackages: Set<String> = emptySet()): SourceApp = when {
            packageName in WECHAT_PACKAGES -> WECHAT
            packageName in QQ_PACKAGES -> QQ
            packageName in WECOM_PACKAGES -> WECOM
            packageName in TIM_PACKAGES -> TIM
            packageName in smsPackages -> SMS
            packageName in KNOWN_SMS_PACKAGES -> SMS
            else -> UNKNOWN
        }

        /** 是否属于本应用关心的来源（腾讯系四类 + 短信）。 */
        fun isMonitored(packageName: String, smsPackages: Set<String>): Boolean =
            packageName in TENCENT_PACKAGES || packageName in smsPackages || packageName in KNOWN_SMS_PACKAGES
    }
}
