package com.shihua66666.nuntra.core

import android.content.Context
import android.os.Build
import com.shihua66666.nuntra.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃记录器。
 *
 * 为什么需要它（以及为什么不用 Toast）：
 *
 *  悬浮窗的 Compose 内容是在 `addView` 返回**之后**由 Choreographer 异步组合/布局的。
 *  那一阶段抛出的异常不会经过调用处的 try-catch，而是直接进入
 *  Thread.defaultUncaughtExceptionHandler → 进程被杀。
 *
 *  更麻烦的是：进程垂死时 Toast 常常来不及显示（主线程已经不可用），
 *  所以「崩了但什么都看不到」是必然结果。
 *
 *  因此这里采用两条互补的手段：
 *   1. 在 uncaught 处理器里把**完整堆栈**写到 SharedPreferences 与文件；
 *   2. 下次启动时由 UI 读出来展示 —— 用户能看到，也能拍照/复制给我们。
 */
object CrashRecorder {

    private const val TAG = "CrashRecorder"

    private const val PREFS = "nuntra_crash"

    private const val KEY_TIME = "last_crash_time"

    private const val KEY_TRACE = "last_crash_trace"

    private const val FILE_NAME = "last_crash.txt"

    /** 兜底上限：堆栈过长时截断，避免 SharedPreferences 存爆。 */
    private const val MAX_TRACE_CHARS = 12000

    @Volatile
    private var installed = false

    /**
     * 安装全局未捕获异常处理器。
     *
     * 必须在 Application.onCreate 里调用且只调用一次。
     * 注意：这里**只记录、不阻断** —— 不改进程退出行为，避免引入新的不稳定因素。
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 记录本身绝不能抛异常，否则会把原始崩溃掩盖掉
            runCatching { record(app, thread, throwable) }
                .onFailure { Logx.swallow(TAG, "record", it) }
            previous?.uncaughtException(thread, throwable)
        }
        Logx.i(TAG, "全局崩溃记录已安装")
    }

    private fun record(app: Context, thread: Thread, throwable: Throwable) {
        val trace = buildString {
            append("时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
            append("\n线程: ").append(thread.name)
            append("\n进程: ").append(BuildConfig.APPLICATION_ID)
            append("\n设备: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            append("  Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")")
            append("\n异常: ").append(throwable.javaClass.name)
            append("\n消息: ").append(throwable.message)
            append("\n\n--- 堆栈 ---\n")
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            append(sw.toString())
        }.take(MAX_TRACE_CHARS)

        // 1) 文件：便于用户导出
        runCatching {
            File(app.filesDir, FILE_NAME).writeText(trace)
        }

        // 2) SharedPreferences：便于下次启动直接读取展示
        runCatching {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TRACE, trace)
                .putLong(KEY_TIME, System.currentTimeMillis())
                .commit()
        }

        // 3) 日志全量输出（堆栈不打折），便于 adb logcat 抓。
        //    同样包一层 runCatching —— 记录阶段本身绝不能再抛。
        runCatching { Logx.e(TAG, "捕获到未处理异常，已记录", throwable) }
    }

    /** 读取上一次崩溃记录；没有则返回 null。 */
    fun lastCrash(context: Context): String? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TRACE, null)
    }.getOrNull()

    /** 清除记录（用户确认看过之后调用）。 */
    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            File(context.filesDir, FILE_NAME).delete()
        }.onFailure { Logx.swallow(TAG, "clear", it) }
    }

    /** 记录时间（用于展示「什么时候崩的」）。 */
    fun lastCrashTime(context: Context): Long = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_TIME, 0L)
    }.getOrDefault(0L)
}
