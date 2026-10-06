package com.shihua66666.nuntra.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 给用户看的反馈。
 *
 * 存在的理由：本应用有大量「不可用」的正常路径（未授权、服务被系统限制、
 * 初始化中）。这些情况**必须让用户看到原因**，而不是静默失败或崩溃。
 *
 * 两条硬约束：
 *  1. 只显示简短中文结论，不弹技术细节（细节进 Logx）；
 *  2. 任何情况下都不许因为「弹提示」本身而崩 —— Toast 在部分 ROM 上
 *     在主线程外调用会抛 RuntimeException，因此这里强制切主线程 + try-catch。
 */
object NtToast {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(context: Context?, message: String) {
        if (context == null || message.isBlank()) return
        // 统一走主线程：调用方可能在协程/IO 线程里（例如 DataStore 回调）
        runCatching {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            } else {
                mainHandler.post {
                    runCatching {
                        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
                    }.onFailure { Logx.swallow("NtToast", "show-post", it) }
                }
            }
        }.onFailure { Logx.swallow("NtToast", "show", it) }
    }
}
