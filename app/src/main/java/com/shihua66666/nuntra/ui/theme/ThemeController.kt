package com.shihua66666.nuntra.ui.theme

import com.shihua66666.nuntra.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 主题控制器。
 *
 * 职责：把持久化的主题 id 映射为 [AppColors]，并暴露为 StateFlow。
 * MainActivity 与 OverlayService 都订阅同一个 controller（ServiceLocator 里的单例），
 * 因此切换主题后**设置页与悬浮窗同时生效**，不需要重启服务。
 */
class ThemeController(
    private val preferences: AppPreferences,
    scope: CoroutineScope,
) {

    private val _colors = MutableStateFlow(AppColors.presetById(AppPreferences.DEFAULT_THEME_ID))

    private val _themeId = MutableStateFlow(AppPreferences.DEFAULT_THEME_ID)

    val colors: StateFlow<AppColors> = _colors.asStateFlow()

    val themeId: StateFlow<String> = _themeId.asStateFlow()

    init {
        scope.launch {
            preferences.themeId
                .catch { emit(AppPreferences.DEFAULT_THEME_ID) }
                .collectLatest { id ->
                    _themeId.value = id
                    _colors.value = AppColors.presetById(id)
                }
        }
    }

    suspend fun selectTheme(id: String) {
        // 先本地生效再落盘：即使写盘失败，UI 也立刻响应，不会出现「点了没反应」
        _themeId.value = id
        _colors.value = AppColors.presetById(id)
        runCatching { preferences.setThemeId(id) }
    }

    /** 供非 Compose 场景（前台通知文案等）同步取色。 */
    fun current(): AppColors = _colors.value

    @Suppress("unused")
    private fun availableThemes(): List<AppColors> = AppColors.PRESETS.map { AppColors.presetById(it.id) }
}
