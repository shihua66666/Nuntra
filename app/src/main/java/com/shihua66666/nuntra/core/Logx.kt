package com.shihua66666.nuntra.core

import android.util.Log
import com.shihua66666.nuntra.BuildConfig

/**
 * 统一日志门面。
 *
 * 设计约定：
 *  · 所有日志统一带 "Nuntra/" 前缀，方便在 Logcat 里用 tag:Nuntra 过滤。
 *  · release 包只保留 warn 以上，避免性能与隐私问题（日志里可能出现联系人名）。
 *  · 本类刻意保持极简，不做参数序列化；调用方自己拼字符串。
 *
 * 不引入第三方日志库：本工程依赖面刻意压到最小。
 */
object Logx {

    private const val PREFIX = "Nuntra/"

    /** 调试模式下开启全部日志。release 恒为 false。 */
    @Volatile
    var verbose: Boolean = BuildConfig.DEBUG

    fun v(tag: String, msg: String) {
        if (verbose) runCatching { Log.v(PREFIX + tag, msg) }
    }

    fun d(tag: String, msg: String) {
        if (verbose) runCatching { Log.d(PREFIX + tag, msg) }
    }

    fun i(tag: String, msg: String) {
        if (verbose) runCatching { Log.i(PREFIX + tag, msg) }
    }

    fun w(tag: String, msg: String) {
        runCatching { Log.w(PREFIX + tag, msg) }
    }

    fun w(tag: String, msg: String, tr: Throwable) {
        runCatching { Log.w(PREFIX + tag, msg, tr) }
    }

    fun e(tag: String, msg: String) {
        runCatching { Log.e(PREFIX + tag, msg) }
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        runCatching { Log.e(PREFIX + tag, msg, tr) }
    }

    /**
     * 异常兜底：任何「不应该崩」的路径都走这里。
     * 本工程的硬性要求是「不崩优先于好看」，所以捕获后必须留下痕迹而不是静默。
     */
    fun swallow(tag: String, where: String, tr: Throwable) {
        w(tag, "已吞掉异常（" + where + "）：" + tr.javaClass.simpleName + ": " + tr.message)
    }
}
