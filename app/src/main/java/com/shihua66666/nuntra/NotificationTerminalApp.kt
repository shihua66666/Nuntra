package com.shihua66666.nuntra

import android.app.Application
import com.shihua66666.nuntra.core.CrashRecorder
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.core.ServiceLocator
import com.shihua66666.nuntra.overlay.OverlayWatchdog

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
        // 启动初始化整体包一层：任何一步失败都只记录，绝不让 App 起不来。
        runCatching {
            // 1) 崩溃记录（最先）：之后任何线程的未捕获异常都会写到磁盘
            CrashRecorder.install(this)
            // 2) 依赖容器
            ServiceLocator.init(this)
            // 3) 启动自愈：上次若在挂载悬浮窗后崩过，自动关掉总开关，
            //    保证「无论悬浮窗出什么问题，用户一定能进 App」。
            val prefs = ServiceLocator.appContainerOrNull?.appPreferences
            if (prefs != null && prefs.overlayCrashFlagBlocking()) {
                Logx.w("App", "检测到上次悬浮窗崩溃，自动关闭总开关以恢复可用")
                kotlinx.coroutines.runBlocking {
                    prefs.setMasterSwitchEnabled(false)
                    prefs.setOverlayCrashFlag(false)
                }
            }
            // 4) 预热（异步，不阻塞）
            ServiceLocator.warmUp()
            // 5) 注册「屏幕亮起」监听（动态注册，必须 —— ACTION_SCREEN_ON 无法静态接收）。
            //    注册在 applicationContext 上且不注销：通知监听服务会让进程长期存活，
            //    进程在，这条监听就有效。
            OverlayWatchdog.registerScreenOn(this)
        }.onFailure { tr ->
            runCatching { Logx.e("App", "启动初始化部分失败（不影响进入界面）", tr) }
        }
        runCatching {
            Logx.i("App", "Nuntra 启动 versionCode=" + BuildConfig.VERSION_CODE + " versionName=" + BuildConfig.VERSION_NAME)
        }
    }
    override fun onTerminate() {
        Logx.i("App", "onTerminate")
        super.onTerminate()
    }
}
