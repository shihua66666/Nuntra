package com.shihua66666.nuntra.overlay

import com.shihua66666.nuntra.core.Logx
import com.shihua66666.nuntra.data.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * 悬浮窗位置与尺寸的持久化。
 *
 * 存 px 而不是 dp：位置是「用户在当前屏幕上摆在哪」的具体坐标，
 * 屏幕旋转或密度变化时统一用 clamp 回屏幕内来纠正，比换算 dp 更直接。
 *
 * 首次启动没有历史位置时，默认落在屏幕右侧中间 —— 这是单手可及且不挡内容的位置。
 */
class OverlayPositionStore(private val preferences: AppPreferences) {

    /**
     * 读取已保存的位置；没有则返回默认位置。
     *
     * @param screenWidthPx  真实屏幕宽度（px）—— 用真实像素而不是 screenWidthDp 换算，
     *                       因为 densityDpi/160 的换算在部分设备上与真实值不一致。
     * @param windowWidth  当前窗口宽度（px），用于默认位置的横向计算
     * @param windowHeight 当前窗口高度（px）
     */
    suspend fun loadOrDefault(
        screenWidthPx: Int,
        screenHeightPx: Int,
        windowWidth: Int,
        windowHeight: Int,
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val screenW = screenWidthPx
        val screenH = screenHeightPx
        val defaultX = (screenW - windowWidth - MARGIN_PX).coerceAtLeast(MARGIN_PX)
        val defaultY = ((screenH - windowHeight) / 2).coerceAtLeast(MARGIN_PX)
        runCatching {
            // firstOrNull 是 Flow 的扩展函数，必须写成 a.firstOrNull()，
            // 写成 firstOrNull(flow) 会得到 Unresolved reference。
            val saved = preferences.overlayPosition.firstOrNull()
            if (saved == null) {
                Logx.i(TAG, "无历史位置，使用默认位置：右中")
                defaultX to defaultY
            } else {
                clamp(saved.x, saved.y, screenW, screenH, windowWidth, windowHeight)
            }
        }.getOrElse { tr ->
            Logx.swallow(TAG, "loadOrDefault", tr)
            // 显式给出 Pair<Int, Int>，否则两个分支的类型会被推成 Any 导致 clamp 参数报错
            defaultX to defaultY
        }
    }

    suspend fun save(x: Int, y: Int) {
        withContext(Dispatchers.IO) {
            runCatching { preferences.setOverlayPosition(x, y) }
                .onFailure { Logx.swallow(TAG, "save", it) }
        }
    }

    suspend fun loadMiniSize(): OverlaySizePx? = withContext(Dispatchers.IO) {
        runCatching {
            val saved: com.shihua66666.nuntra.data.OverlaySize? = preferences.overlaySize.firstOrNull()
            if (saved == null || saved.width <= 0 || saved.height <= 0) {
                null
            } else {
                OverlaySizePx(saved.width, saved.height)
            }
        }.getOrElse { null }
    }

    suspend fun saveMiniSize(size: OverlaySizePx) {
        withContext(Dispatchers.IO) {
            runCatching { preferences.setOverlaySize(size.width, size.height) }
                .onFailure { Logx.swallow(TAG, "saveMiniSize", it) }
        }
    }

    companion object {
        private const val TAG = "OverlayPositionStore"

        /** 屏幕边缘留白（px）：避免悬浮窗贴死边缘导致拖不回来。 */
        const val MARGIN_PX = 12

        /**
         * 把坐标夹回屏幕内。
         *
         * 屏幕旋转、分辨率变化、或历史数据来自更大屏幕时都必须调用，
         * 否则悬浮窗会跑到屏幕外，用户看不到也拖不回来 —— 这是最常见的「浮窗消失」假象。
         */
        fun clamp(
            x: Int,
            y: Int,
            screenW: Int,
            screenH: Int,
            windowW: Int,
            windowH: Int,
        ): Pair<Int, Int> {
            val maxX = (screenW - windowW - MARGIN_PX).coerceAtLeast(MARGIN_PX)
            val maxY = (screenH - windowH - MARGIN_PX).coerceAtLeast(MARGIN_PX)
            return x.coerceIn(MARGIN_PX, maxX) to y.coerceIn(MARGIN_PX, maxY)
        }
    }
}
