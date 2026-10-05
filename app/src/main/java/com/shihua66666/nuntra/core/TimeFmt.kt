package com.shihua66666.nuntra.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 时间格式化：全部输出「给人看的短文本」，用于消息卡片右下角。
 *
 * 用 SimpleDateFormat 而不是 java.time：调用频率低、无需池化，
 * 且避免在 minSdk 29 之外再引入额外兼容分支。
 * 注意 SimpleDateFormat 非线程安全，因此每次调用新建实例。
 */
object TimeFmt {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /** 刚刚 / N 分钟前 / N 小时前 / 昨天 HH:mm / MM-dd HH:mm / yyyy-MM-dd HH:mm:ss */
    fun relative(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val delta = now - timestamp
        return when {
            delta < 0 -> absolute(timestamp)
            delta < MINUTE -> "刚刚"
            delta < HOUR -> (delta / MINUTE).toString() + " 分钟前"
            isSameDay(timestamp, now) -> (delta / HOUR).toString() + " 小时前"
            isYesterday(timestamp, now) -> "昨天 " + hhmm(timestamp)
            isSameYear(timestamp, now) -> MMdd(timestamp) + " " + hhmm(timestamp)
            else -> absolute(timestamp)
        }
    }

    /** HH:mm */
    fun hhmm(timestamp: Long): String = format("HH:mm", timestamp)

    /** yyyy-MM-dd HH:mm:ss */
    fun absolute(timestamp: Long): String = format("yyyy-MM-dd HH:mm:ss", timestamp)

    /** MM-dd */
    fun MMdd(timestamp: Long): String = format("MM-dd", timestamp)

    /** 秒数转「M 分 S 秒」/「S 秒」，用于重复提醒倒计时展示。 */
    fun duration(seconds: Long): String {
        if (seconds < 0) return "0 秒"
        val m = TimeUnit.SECONDS.toMinutes(seconds)
        val s = seconds % 60
        return if (m <= 0) s.toString() + " 秒" else m.toString() + " 分 " + s.toString() + " 秒"
    }

    private fun format(pattern: String, timestamp: Long): String =
        runCatching { SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp)) }.getOrDefault("")

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
            ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(a: Long, now: Long): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        c.add(Calendar.DAY_OF_YEAR, -1)
        return isSameDay(a, c.timeInMillis)
    }

    private fun isSameYear(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
    }
}
