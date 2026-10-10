package com.shihua66666.nuntra.monitor

import android.service.notification.StatusBarNotification
import com.shihua66666.nuntra.core.Keywords
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.model.MonitorScope
import com.shihua66666.nuntra.model.SourceApp

/** 过滤结论。 */
sealed interface FilterDecision {
    /** 允许入库。 */
    data class Accept(val source: SourceApp) : FilterDecision

    /** 丢弃，附带原因（用于日志与真机排查）。 */
    data class Reject(val reason: String) : FilterDecision
}

/** 过滤上下文的只读快照。 */
data class FilterContext(
    /** 总闸。false 时在收到通知的第一时间直接丢弃，不做任何解析。 */
    val masterSwitchEnabled: Boolean,
    val scope: MonitorScope,
    /** 解析出的短信 App 包名集合。 */
    val smsPackages: Set<String>,
    val watchedContactNames: List<String>,
    val keywords: Set<String>,
    val smsMonitoringEnabled: Boolean,
    val smsKeywords: Set<String>,
    /** 总关注人数（含被停用的也计入，它们同样代表「用户配置过关注人」）。 */
    val watchedConfiguredCount: Int,
)

/**
 * 通知过滤器。
 *
 * 设计核心：**分层短路**。
 *  第 0 层 总闸        —— 关闭时连 extras 都不读，彻底省电（这是本步的硬性要求）。
 *  第 1 层 包名白名单  —— 不在名单里的直接丢弃。
 *  第 2 层 分闸        —— 微信/QQ/企业微信/TIM 各自的监控开关。
 *  第 3 层 噪音过滤    —— ongoing、空内容（汇总通知**不再**在这里丢弃，见 decide 内注释）。
 *  第 4 层 语义匹配    —— 非短信：关注人是必要条件，关键词只在其上收窄；两者都空则全部放行。
 *  第 5 层 短信专用    —— 短信关键词**独立生效**，不要求命中关注人；取不到内容时丢弃。
 *
 * 为什么要这么分层：把「便宜的判断」放在前面，
 * 让绝大多数无关通知在字符串处理之前就被扔掉。
 */
object NotificationFilter {

    private const val TAG = "Filter"

    const val DROP_MASTER_OFF = "总开关关闭"

    fun decide(
        sbn: StatusBarNotification,
        extras: ExtractedNotification?,
        ctx: FilterContext,
    ): FilterDecision {
        // ── 第 0 层：总闸。要求「不做任何解析」，因此 extras 允许为 null。
        if (!ctx.masterSwitchEnabled) return FilterDecision.Reject(DROP_MASTER_OFF)

        val packageName = sbn.packageName ?: return FilterDecision.Reject("包名为空")

        // ── 第 1 层：包名白名单
        if (!SourceApp.isMonitored(packageName, ctx.smsPackages)) {
            return FilterDecision.Reject("包名不在监控白名单")
        }
        val source = SourceApp.fromPackage(packageName, ctx.smsPackages)

        // ── 第 2 层：分闸
        when (source) {
            SourceApp.WECHAT -> if (!ctx.scope.wechat) return FilterDecision.Reject("微信监控已关闭")
            SourceApp.QQ -> if (!ctx.scope.qq) return FilterDecision.Reject("QQ 监控已关闭")
            SourceApp.WECOM -> if (!ctx.scope.weCom) return FilterDecision.Reject("企业微信监控已关闭")
            SourceApp.TIM -> if (!ctx.scope.tim) return FilterDecision.Reject("TIM 监控已关闭")
            SourceApp.SMS -> if (!ctx.smsMonitoringEnabled) return FilterDecision.Reject("短信监控已关闭")
            SourceApp.UNKNOWN -> return FilterDecision.Reject("来源未识别")
        }

        // ── 第 3 层：噪音过滤（此时才需要 extras）
        val extracted = extras ?: return FilterDecision.Reject("无法解析通知内容")

        // 持续通知（正在输入、N 条未读等）没有展示价值，且会反复刷新
        if (sbn.isOngoing) return FilterDecision.Reject("ongoing 常驻通知")

        // ★★ 汇总通知（FLAG_GROUP_SUMMARY）不再一律丢弃 ★★
        //
        //   之前这里直接 Reject("汇总通知")。但微信群收到多条消息时，
        //   微信恰恰只发一条带 FLAG_GROUP_SUMMARY 的「群名（3 条新消息）」——
        //   于是群聊消息一条都进不来（用户反馈的「群聊收不到」）。
        //
        //   现在：汇总通知照常参与下面的匹配；命中关注人即入库。
        //   重复刷新由上层（NuntraNotificationListener）用「规范化摘要键」去重，
        //   保证同一条合并通知只入库一次。
        val haystacks = extracted.haystacks
        // 名字候选：关注人匹配只看这一组（TITLE / CONVERSATION_TITLE / SUB_TEXT / 发送者），
        // 而不是整个正文 —— 正文里出现同名不算「消息来自这个人」。
        val names = extracted.nameCandidates

        // ── 第 5 层（提前到匹配之前）：短信专用规则
        // 短信要求「必须命中关键词」，因此先做关键词判定更省事，也避免空内容往下走。
        if (source == SourceApp.SMS) {
            return decideSms(extracted, ctx, haystacks)
        }

        // ── 第 4 层：关注人 / 关键词
        val hasKeywords = ctx.keywords.isNotEmpty()
        val hasWatched = ctx.watchedConfiguredCount > 0

        // 需求明确：关注人和关键词都为空时全部放行，避免新装用户以为应用坏了
        if (!hasKeywords && !hasWatched) {
            if (!extracted.hasUsableBody) return FilterDecision.Reject("无可用正文")
            return FilterDecision.Accept(source)
        }

        if (hasWatched) {
            val contactHit = ctx.watchedContactNames.any { name -> Keywords.contactHit(names, name) }
            if (!contactHit) {
                return FilterDecision.Reject("未命中任何关注人（非短信关键词只在关注人的消息内生效）")
            }
            // ★ 命中关注人后，若配置了关键词，则必须**再命中**关键词。
            //   这就是「关键词与关注人绑定」：关键词只收窄、不放宽。
            if (hasKeywords && Keywords.hits(haystacks, ctx.keywords).isEmpty()) {
                return FilterDecision.Reject("命中关注人但未命中关键词")
            }
            // ★★ 命中关注人即视为有效消息 ★★
            //   即使是正文为空的群聊合并通知（「群名（3 条新消息）」）也要放行，
            //   否则又会回到「群聊收不到」。入库时 contact 字段是群名，不会丢弃。
            return FilterDecision.Accept(source)
        }

        // 走到这里：没有配置任何关注人。
        // 非短信关键词**不独立生效**（用户明确要求），因此这里一律不入库，
        // 并给出可读原因 —— 用户能在日志/排查里立刻看懂为什么没消息。
        return FilterDecision.Reject("只配置了关键词但没有关注人：非短信关键词必须与关注人绑定")
    }

    /**
     * 短信专用判定。
     *
     * 需求：
     *  · onlyUseNotificationListener：不申请 RECEIVE_SMS，只读通知
     *  · EXTRA_TEXT 或 EXTRA_TITLE 必须包含关键词之一才入库，否则丢弃
     *  · 被合并的短信（取不到具体内容）直接丢弃
     */
    private fun decideSms(
        extracted: ExtractedNotification,
        ctx: FilterContext,
        haystacks: List<String?>,
    ): FilterDecision {
        // 合并短信：例如「你有 3 条新短信」，取不到具体内容 → 丢弃
        if (!extracted.hasUsableBody) {
            return FilterDecision.Reject("短信内容为空或被系统合并，丢弃")
        }

        // 用户把关键词删空时退化为「全部短信放行」，而不是一条都不入
        // —— 后者会让用户以为功能坏了，且与「关键词是过滤器」的直觉不符。
        if (ctx.smsKeywords.isEmpty()) return FilterDecision.Accept(SourceApp.SMS)

        val hitKeywords = Keywords.hits(haystacks, ctx.smsKeywords)
        if (hitKeywords.isEmpty()) return FilterDecision.Reject("短信未命中任何关键词")

        Logx.d(TAG, "短信命中关键词：" + hitKeywords.joinToString("/"))
        return FilterDecision.Accept(SourceApp.SMS)
    }
}
