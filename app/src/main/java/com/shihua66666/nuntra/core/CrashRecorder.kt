package com.shihua66666.nuntra.core

import android.content.Context
import android.os.Build
import android.os.Environment
import com.shihua66666.nuntra.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃记录器（**只记录，绝不做任何有风险的事**）。
 *
 * 设计红线（这是被一次「启动死循环」事故逼出来的）：
 *
 *  1. **不在启动路径做任何 I/O 决策**。记录结果放在一个静态字段里，
 *     需要时读字段即可 —— 不读文件、不读 SharedPreferences。
 *  2. **不在启动路径渲染任何崩溃 UI**。上一版在 MainActivity 的组合期读取并渲染
 *     崩溃页，一旦该页自身抛异常，就会「每次启动必崩」→ 用户进不去 App。
 *     现在崩溃详情只在设置页由用户主动点击才展示。
 *  3. **每处可能抛异常的操作都独立 try-catch**，任何一环失败都不影响其余，
 *     更不影响进程存活。
 *  4. 使用 `commit()`（同步）而不是 `apply()`：崩溃时进程随时可能消失，
 *     异步写入可能来不及落盘。
 *
 * 另外同时写一份到**外部存储**（`/sdcard/Android/data/<包名>/files/`），
 * 这样即使 App 完全进不去，也能用文件管理器或 adb 把日志取出来。
 */
object CrashRecorder {

    private const val TAG = "CrashRecorder"

    private const val PREFS = "nuntra_crash"

    private const val KEY_TRACE = "last_crash_trace"

    private const val FILE_NAME = "last_crash.txt"

    private const val MAX_TRACE_CHARS = 12000

    @Volatile
    private var installed = false

    /**
     * 最近一次未捕获异常的完整堆栈。
     *
     * ★ 这是 UI 读取的唯一来源：纯内存字段，读取不可能失败、不可能抛异常。
     *   注意它是**本进程**的；跨进程（下次启动）要看磁盘那份。
     */
    @Volatile
    var lastStackTrace: String? = null
        private set

    /** 最近一次崩溃发生的时间戳。 */
    @Volatile
    var lastCrashTime: Long = 0L
        private set

    /**
     * 安装全局未捕获异常处理器。
     *
     * 这个方法本身**绝不会抛异常**（整体包在 try-catch 里）。
     * 即使安装失败，也只是失去崩溃记录能力，不影响 App 正常运行。
     */
    fun install(context: Context) {
        try {
            if (installed) return
            installed = true
            val app = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                // 记录阶段整体兜底：绝不能让「记录崩溃」变成新的崩溃
                try {
                    record(app, thread, throwable)
                } catch (ignored: Throwable) {
                    // 这里连日志都不打（日志本身也可能抛），保持绝对安全
                }
                // 交回原处理器：不改变系统的退出行为，避免留下半死进程
                try {
                    previous?.uncaughtException(thread, throwable)
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
            // 安装失败也不允许影响启动
        }
    }

    private fun record(app: Context, thread: Thread, throwable: Throwable) {
        // 若是悬浮窗相关崩溃，写入与 AppPreferences 相同的键，
        // 供下次启动的「自愈」逻辑自动关闭总开关，避免启动死循环。
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val full = sw.toString()
            if (full.contains("Overlay") || full.contains("overlay")) {
                app.getSharedPreferences("nuntra_prefs", Context.MODE_PRIVATE).edit()
                    .putBoolean("overlay_crash_flag", true)
                    .commit()
            }
        } catch (ignored: Throwable) {
        }
        // 1) 先塞进内存字段：这是最不可能失败的一步，务必最先做
        val trace = try {
            buildTrace(thread, throwable)
        } catch (ignored: Throwable) {
            "构建堆栈失败：" + throwable.javaClass.name
        }
        lastStackTrace = trace
        lastCrashTime = System.currentTimeMillis()

        // 2) 文件：内部存储（应用私有）
        try {
            File(app.filesDir, FILE_NAME).writeText(trace)
        } catch (ignored: Throwable) {
        }

        // 3) 文件：外部存储（可被文件管理器/adb 直接取走，用于 App 进不去的情况）
        try {
            val dir = app.getExternalFilesDir(null)
            if (dir != null) File(dir, FILE_NAME).writeText(trace)
        } catch (ignored: Throwable) {
        }

        // 4) SharedPreferences：同步 commit，保证崩溃瞬间也能落盘
        try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TRACE, trace)
                .putLong("last_crash_time", lastCrashTime)
                .commit()
        } catch (ignored: Throwable) {
        }

        // 5) Logcat：约定一个稳定 TAG，便于 adb 过滤
        try {
            Logx.e(TAG, "未处理异常已记录：" + throwable.javaClass.name)
        } catch (ignored: Throwable) {
        }
    }

    private fun buildTrace(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val head = buildString {
            append("时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
            append("\n线程: ").append(thread.name)
            append("\n进程: ").append(BuildConfig.APPLICATION_ID)
            append("\n设备: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            append("  Android ").append(Build.VERSION.RELEASE)
            append(" (API ").append(Build.VERSION.SDK_INT).append(")")
            append("\n异常: ").append(throwable.javaClass.name)
            append("\n消息: ").append(throwable.message)
            append("\n\n--- 堆栈 ---\n")
        }
        return (head + sw.toString()).take(MAX_TRACE_CHARS)
    }

    /**
     * 读取磁盘上**上一次启动**记录的崩溃堆栈。
     *
     * ★ 只在用户主动点击「查看上次崩溃」时调用，**不在启动路径调用**。
     *   读取失败一律返回 null，绝不抛异常。
     */
    fun readPersisted(context: Context): String? = try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TRACE, null)
            ?: File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText()
    } catch (ignored: Throwable) {
        null
    }

    /** 清除记录。失败也不抛。 */
    fun clear(context: Context) {
        lastStackTrace = null
        lastCrashTime = 0L
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        } catch (ignored: Throwable) {
        }
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (ignored: Throwable) {
        }
        try {
            context.getExternalFilesDir(null)?.let { File(it, FILE_NAME).delete() }
        } catch (ignored: Throwable) {
        }
    }

    /** 外部存储里那份日志的绝对路径（用于告知用户去哪里取）；取不到则返回 null。 */
    fun externalLogPath(context: Context): String? = try {
        context.getExternalFilesDir(null)?.let { File(it, FILE_NAME).absolutePath }
    } catch (ignored: Throwable) {
        null
    }

    /** 内部存储里那份日志的绝对路径。 */
    fun internalLogPath(context: Context): String = try {
        File(context.filesDir, FILE_NAME).absolutePath
    } catch (ignored: Throwable) {
        "(不可用)"
    }
}
