package com.shihua66666.nuntra

import android.app.Application
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
        ServiceLocator.init(this)
        ServiceLocator.warmUp()
        Logx.i("App", "Nuntra 启动 versionCode=" + BuildConfig.VERSION_CODE + " versionName=" + BuildConfig.VERSION_NAME)
    }

    override fun onTerminate() {
        Logx.i("App", "onTerminate")
        super.onTerminate()
    }
}
