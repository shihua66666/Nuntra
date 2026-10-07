package com.shihua66666.nuntra.overlay

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.ui.theme.AppColors
import com.shihua66666.nuntra.ui.theme.LocalAppColors
import com.shihua66666.nuntra.ui.theme.LocalMonoFamily
import com.shihua66666.nuntra.ui.theme.MonoFont
import com.shihua66666.nuntra.ui.theme.NuntraShapes
import com.shihua66666.nuntra.ui.theme.nuntraTypography

/**
 * 悬浮窗的宿主生命周期。
 *
 * ★★ 这是整个悬浮窗方案里最容易崩的一环，必须按下面的方式实现 ★★
 *
 * 崩溃原因：ComposeView 被 add 到 WindowManager 时，它上方没有任何 Activity，
 * 因此 ViewTreeLifecycleOwner / ViewTreeSavedStateRegistryOwner 取不到值。
 * Compose 在 attach 阶段就会抛 IllegalStateException；
 * 即使侥幸不崩，也会因为拿不到 lifecycle 而无法正确释放（内存泄漏）。
 *
 * 解决方式（四步，缺一不可）：
 *  1. 本类实现 LifecycleOwner + SavedStateRegistryOwner + ViewModelStoreOwner
 *     （外加 OnBackPressedDispatcherOwner，让悬浮窗里的 BackHandler 可用）；
 *  2. context 用 [OverlayContextWrapper] —— ComposeView 构造函数内部会从 context
 *     取 parent SavedStateRegistryOwner，普通 Context 取不到；
 *  3. 在宿主 View 上手动 setViewTreeLifecycleOwner /
 *     setViewTreeSavedStateRegistryOwner / setViewTreeViewModelStoreOwner；
 *  4. 显式设置 ViewCompositionStrategy.DisposeOnDetachedFromWindow，
 *     否则窗口移除后组合仍存活（默认策略在窗口场景不符预期）。
 *
 * 生命周期顺序（顺序错了同样出问题）：
 *   attach:  onCreate() → addView() → onStart()
 *   detach:  removeView() → onDestroy()
 * 即**先 removeView，再 destroy**；反过来正在 detach 的组合会看到 DESTROYED 状态。
 */
class OverlayLifecycleProvider :
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner,
    OnBackPressedDispatcherOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)

    private val savedStateController = SavedStateRegistryController.create(this)

    private val store = ViewModelStore()

    private val backDispatcher = OnBackPressedDispatcher()

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override val viewModelStore: ViewModelStore get() = store

    override val onBackPressedDispatcher: OnBackPressedDispatcher get() = backDispatcher

    private var lastState: Lifecycle.State = Lifecycle.State.INITIALIZED

    /** 必须在 addView 之前调用。 */
    fun onCreate() {
        if (lastState != Lifecycle.State.INITIALIZED) return
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        moveTo(Lifecycle.State.CREATED)
    }

    /** 必须在 addView 成功之后调用。 */
    fun onStart() {
        moveTo(Lifecycle.State.RESUMED)
    }

    fun onPause() {
        moveTo(Lifecycle.State.CREATED)
    }

    /** 必须在 removeView 之后调用。 */
    fun onDestroy() {
        if (lastState == Lifecycle.State.DESTROYED) return
        if (lastState.isAtLeast(Lifecycle.State.RESUMED)) moveTo(Lifecycle.State.CREATED)
        moveTo(Lifecycle.State.DESTROYED)
        store.clear()
    }

    private fun moveTo(target: Lifecycle.State) {
        if (lastState == target) return
        runCatching { lifecycleRegistry.currentState = target }
            .onFailure { Logx.swallow(TAG, "moveTo " + target.name, it) }
        lastState = target
    }

    private companion object {
        const val TAG = "OverlayLifecycle"
    }
}

/**
 * 给 ComposeView 用的 context。
 *
 * 必须实现 SavedStateRegistryOwner：ComposeView 构造时会执行
 * setParentSavedStateRegistryOwner(context, ...)，普通 Context 在这里取不到 owner。
 */
class OverlayContextWrapper(
    base: Context,
    owner: SavedStateRegistryOwner,
) : ContextWrapper(base), SavedStateRegistryOwner by owner

/**
 * 悬浮窗 Compose 内容宿主。
 *
 * 与 Activity 完全解耦：只负责「造 View / 挂 owner / 通知生命周期」，
 * 不做任何窗口操作（那是 OverlayWindowController 的职责）。
 */
class OverlayComposeHost(private val appContext: Context) : OverlayComposeHostAccessor {

    private val owner = OverlayLifecycleProvider()

    private var composeView: ComposeView? = null

    private var currentColors: AppColors = AppColors.GLAID_BLUE

    private var content: (@Composable () -> Unit)? = null

    private val monoFamily: FontFamily by lazy { MonoFont.load(appContext) }

    /**
     * 创建宿主 View（尚未 attach 到窗口）。
     *
     * 调用方（OverlayWindowController）拿到的这个 View 就是最终 addView 的对象。
     * 它实现 [OverlayComposeHostAccessor]，控制器会在正确的时机回调生命周期。
     */
    fun createView(colors: AppColors, content: @Composable () -> Unit): View {
        currentColors = colors
        this.content = content
        val wrappedContext = OverlayContextWrapper(appContext, owner)
        val view = ComposeView(wrappedContext).apply {
            // ★ 三个 ViewTree owner 一个都不能少。注意包名差异：
            //   lifecycle / viewModelStore 来自 androidx.lifecycle，
            //   savedStateRegistry 来自 androidx.savedstate —— 混写会编译不过。
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)

            // ★ 必须显式指定：默认策略在「窗口被移除」这条路径上不会按预期释放组合。
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)

            setContent { OverlayContentShell(colors, monoFamily, owner) { content() } }
        }
        composeView = view
        return view
    }

    /**
     * 把三个 ViewTree owner 注入到**真正被 addView 的那个根 View** 上。
     *
     * ★★★ 这是修「ViewTreeLifecycleOwner not found」的关键 ★★★
     *
     * Compose 在创建 Recomposer 时，是从**窗口的根 View** 出发向上查找
     * ViewTreeLifecycleOwner（见 androidx.compose.ui.platform.WindowRecomposer_androidKt
     * .createLifecycleAwareWindowRecomposer）。
     *
     * 悬浮窗这一棵 View 树的根是 [OverlayTouchInterceptor]（它才是 addView 的对象），
     * 而 ComposeView 只是它的**子 View**。若 owner 只挂在子 ComposeView 上，
     * 从根向上查找必然失败，抛出：
     *
     *     IllegalStateException: ViewTreeLifecycleOwner not found from OverlayTouchInterceptor
     *
     * 注意这是**异步**崩溃：addView 返回成功，随后挂载到窗口时才创建 Recomposer，
     * 所以调用处包 try-catch 是兜不住的。
     *
     * 因此 owner 必须挂在根 View 上。ComposeView 上也保留一份（见 [createView]）作为冗余：
     * 查找总是取离起点最近的那份，两边都有时行为一致。
     *
     * 调用时机要求：**必须在 addView 之前**（顺序见 OverlayService.syncOverlay）。
     */
    fun bindOwnersTo(root: View) {
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeViewModelStoreOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
    }

    /**
     * addView 前的最后一道自检：确认三个 owner 都能从 [root] 查到。
     *
     * 返回 null 表示 OK；否则返回缺失项的人话描述。
     * 存在的意义：把「挂载后异步崩溃」变成「挂载前的明确报错」——
     * 前者用户什么都看不到，后者能当场指出问题。
     */
    fun verifyOwners(root: View): String? {
        if (root.findViewTreeLifecycleOwner() == null) return "ViewTreeLifecycleOwner 缺失"
        if (root.findViewTreeViewModelStoreOwner() == null) return "ViewTreeViewModelStoreOwner 缺失"
        if (root.findViewTreeSavedStateRegistryOwner() == null) return "ViewTreeSavedStateRegistryOwner 缺失"
        return null
    }

    /**
     * 主题切换：重建内容。
     *
     * 之所以重建而不是只更新颜色状态：悬浮窗的 ComposeView 不在 Activity 的组合树里，
     * 没有外部的重组驱动；直接重新 setContent 最可靠，也避免残留状态。
     */
    fun updateTheme(colors: AppColors) {
        val view = composeView ?: return
        val body = content ?: return
        if (currentColors == colors) return
        currentColors = colors
        runCatching { view.setContent { OverlayContentShell(colors, monoFamily, owner) { body() } } }
            .onFailure { Logx.swallow(TAG, "updateTheme", it) }
    }

    // ── OverlayComposeHostAccessor ───────────────────────────────

    /** addView 之前：推进到 CREATED。 */
    override fun onBeforeAdd() = owner.onCreate()

    /** addView 成功之后：推进到 RESUMED。 */
    override fun onAfterAdd() = owner.onStart()

    /** removeView 之后：推进到 DESTROYED 并清空 ViewModelStore。 */
    override fun onAfterRemove() {
        composeView = null
        owner.onDestroy()
    }

    private companion object {
        const val TAG = "OverlayComposeHost"
    }
}

/**
 * 悬浮窗内容壳：装配主题与 CompositionLocal。
 *
 * 与 ui/theme/NuntraTheme 的区别：这里显式提供 LocalViewModelStoreOwner，
 * 因此悬浮窗内部可以安全使用 viewModel()；NuntraTheme 面向 Activity，无需处理这一点。
 */
@Composable
private fun OverlayContentShell(
    colors: AppColors,
    mono: FontFamily,
    owner: ViewModelStoreOwner,
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(colors) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.background,
            secondary = colors.infoBlue,
            onSecondary = colors.background,
            tertiary = colors.sourceWeChat,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.panel,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.panelElevated,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            outlineVariant = colors.border,
            error = colors.warning,
            onError = colors.textPrimary,
        )
    }
    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalMonoFamily provides mono,
        LocalViewModelStoreOwner provides owner,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = nuntraTypography(mono),
            shapes = NuntraShapes,
        ) {
            content()
        }
    }
}
