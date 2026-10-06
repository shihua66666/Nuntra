package com.shihua66666.nuntra

import android.app.Application
import com.shihua66666.nuntra.core.CrashRecorder
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.core.ServiceLocator

/**
 * 应用入口。
 *
 * 职责只有两件：
 *  1. 初始化 [ServiceLocator]（轻量依赖容器，替代 Hilt，避免注解处理链路拖慢首次 Sync）。
 *  2. 预热标签仓库，避免首个 UI 帧期间读磁盘。
 *
 * 注意：这里【不】启动前台服务、【不】做任何耗时 IO。
 * 前台服务由用户在首页显式开启，避免「打开 App 就弹权限与常驻通知」的突兀体验。
 */
class NotificationTerminalApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // ★ 最先安装崩溃记录：之后任何线程的未捕获异常都会被写到磁盘，下次启动可读出展示。
        //   悬浮窗 Compose 的异步渲染崩溃无法被调用处 try-catch 捕获，只能靠它。
        runCatching { CrashRecorder.install(this) }
        ServiceLocator.init(this)
        ServiceLocator.warmUp()
        Logx.i("App", "Nuntra 启动 versionCode=" + BuildConfig.VERSION_CODE + " versionName=" + BuildConfig.VERSION_NAME)
    }

    override fun onTerminate() {
        Logx.i("App", "onTerminate")
        super.onTerminate()
    }
}
