package com.shihua66666.nuntra.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import com.shihua66666.nuntra.data.AppPreferences
import com.shihua66666.nuntra.data.MessageStore
import com.shihua66666.nuntra.data.SettingsRepository
import com.shihua66666.nuntra.data.TagRepository
import com.shihua66666.nuntra.notify.TagResolver
import com.shihua66666.nuntra.ui.theme.ThemeController
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 轻量依赖容器。
 *
 * 为什么不用 Hilt：
 *  · 单模块工程，注入点不到 20 个；Hilt 会引入 KSP 与注解处理链路，
 *    在「无法在本机编译验证」的前提下属于不成比例的 Sync 风险。
 *  · 需要换 Hilt 时，只要把 [AppContainer] 的构造搬进 Module 即可，调用方不用改。
 *
 * 生命周期：与进程一致。
 */
object ServiceLocator {

    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var container: AppContainer? = null

    /** 供应用级任务使用的作用域。所有仓库内部各自管理自己的协程，不依赖这里。 */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("Nuntra-App"))
    }

    /**
     * 初始化失败的原因（UI 会展示它）。
     *
     * 只在 init 捕获到异常时写入；正常情况下恒为 null。
     */
    @Volatile
    var initFailure: String? = null
        private set

    /**
     * 初始化容器。**不抛异常** —— 这是「启动不闪退」的第一道闸门。
     *
     * 失败时记录到 [initFailure]，由 UI 显示一句人话，而不是让进程直接死。
     * Application 阶段（DataStore 损坏、被系统限制等）出问题时同样适用。
     */
    fun init(context: Context) {
        if (container != null) return
        synchronized(this) {
            if (container != null) return
            try {
                val app = context.applicationContext
                appContext = app
                container = AppContainer(app)
                initFailure = null
            } catch (tr: Throwable) {
                val reason = tr.javaClass.simpleName + ": " + (tr.message ?: "")
                initFailure = reason
                Logx.e("ServiceLocator", "容器初始化失败：" + reason, tr)
                container = null
            }
        }
    }

    /**
     * 可空访问器：给 UI 层保命用。
     *
     * 为什么需要它：如果 Application.onCreate 里初始化失败，直接用 [c]
     * 会在组合期抛异常 → 界面白屏或闪退，用户连「哪里出错了」都看不到。
     * UI 层用这个访问器就能显示一句人话。
     */
    val appContainerOrNull: AppContainer? get() = container

    /** 初始化是否已完成。 */
    val isInitialized: Boolean get() = container != null

    /**
     * 进程内一定已初始化；未初始化属于编程错误。
     *
     * 注意：UI 层不要直接用这个，用 [appContainerOrNull] 以免闪退。
     */
    val c: AppContainer
        get() = container ?: error("ServiceLocator 尚未初始化：请确认 AndroidManifest 的 application android:name 指向 NotificationTerminalApp")

    val context: Context
        get() = appContext ?: error("ServiceLocator 尚未初始化")

    /** Application 里初始化后调用一次：预热标签迁移，避免首个 UI 帧期间读磁盘。 */
    fun warmUp() {
        appScope.launch {
            runCatching { c.tagRepository.ensureInitialized() }
                .onFailure { Logx.swallow("ServiceLocator", "warmUp", it) }
        }
    }

    /** 仅测试使用。 */
    fun resetForTest() {
        container = null
        appContext = null
    }
}

/**
 * 应用级依赖集合。
 *
 * 这里只放「跨页面共享、无 UI 依赖」的对象。
 * 悬浮窗相关对象由 OverlayService 自己持有：它们与 Service 生命周期强绑定，
 * 放进来会造成泄漏风险，也会让「停服务」变成「状态没清干净」。
 */
class AppContainer(private val app: Context) {

    val packageManager: PackageManager get() = app.packageManager

    val messageStore: MessageStore by lazy {
        // 排序依赖标签优先级，这里注入一次；lambda 是惰性的，不会造成初始化循环
        MessageStore(tagPriorityOf = { tagId -> tagRepository.priorityOf(tagId) })
    }

    val tagRepository: TagRepository by lazy { TagRepository(app) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(app, appPreferences) }

    val appPreferences: AppPreferences by lazy { AppPreferences(app) }

    val tagResolver: TagResolver by lazy {
        TagResolver(tagRepository = tagRepository, settingsRepository = settingsRepository)
    }

    val themeController: ThemeController by lazy { ThemeController(appPreferences, ServiceLocator.appScope) }
}
