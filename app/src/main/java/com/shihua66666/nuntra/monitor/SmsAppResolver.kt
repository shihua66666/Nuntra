package com.shihua66666.nuntra.monitor

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.model.SourceApp

/**
 * 短信 App 包名识别。
 *
 * 只用通知监听，**不申请 READ_SMS / RECEIVE_SMS**，因此不能查询短信数据库。
 * 可用的手段只有三种，按可靠性排序：
 *
 *  1. Telephony.Sms.getDefaultSmsPackage(context) —— 最准，无需权限。
 *  2. 用户在其他应用里已确认并保存的包名（SettingsRepository.resolvedSmsPackages）。
 *  3. 已安装的、已知短信包名（SourceApp.KNOWN_SMS_PACKAGES）与系统查询结果取交集概率最高，
 *     但仍可能漏（小众 ROM 的短信包名不在名单里）。
 *
 * 三种都失败时返回空集合，此时设置页会提示用户手动指定：
 * 手动指定的结果会存进第 2 种，之后就不再依赖自动识别。
 */
object SmsAppResolver {

    private const val TAG = "SmsAppResolver"

    /**
     * 解析结果。
     *
     * @param packages 判定为短信 App 的包名集合
     * @param needsManualSelection true 表示自动识别不可靠，建议用户在设置页指定
     */
    data class Resolution(val packages: Set<String>, val needsManualSelection: Boolean)

    fun resolve(context: Context, userConfirmed: Set<String>): Resolution {
        val result = LinkedHashSet<String>()

        // 1) 系统默认短信 App
        val defaultSms = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
        if (!defaultSms.isNullOrBlank()) result.add(defaultSms)

        // 2) 用户确认过的
        result.addAll(userConfirmed.filter { it.isNotBlank() })

        // 3) 已知短信包名中，本机确实安装了的那部分
        val installedKnown = SourceApp.KNOWN_SMS_PACKAGES.filter { isInstalled(context, it) }
        result.addAll(installedKnown)

        // 4) 兜底：能响应 smsto: 的已安装应用（部分 ROM 的短信 App 不在已知名单里）
        if (result.isEmpty()) {
            result.addAll(querySmsHandlers(context))
        }

        val needsManual = result.isEmpty()
        if (needsManual) {
            Logx.w(TAG, "未能自动识别短信 App，需要在设置页手动指定")
        } else {
            Logx.i(TAG, "短信 App 识别结果：" + result.joinToString(", "))
        }
        return Resolution(result, needsManual)
    }

    private fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /**
     * 查询能处理 smsto: 的应用。
     *
     * 需要 Manifest 里的 <queries> 声明（已声明 SENDTO/smsto 与 VIEW/sms），
     * 否则在 targetSdk 30+ 上返回空列表。
     */
    private fun querySmsHandlers(context: Context): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:")).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it.isNotBlank() }
            .toSet()
    }.getOrElse { tr ->
        Logx.swallow(TAG, "querySmsHandlers", tr)
        emptySet()
    }
}
