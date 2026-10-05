package com.shihua66666.nuntra.ui.theme

import androidx.compose.ui.graphics.Color
import com.shihua66666.nuntra.model.SourceApp

/**
 * 来源色：只表示「消息来自微信 / QQ / 短信」，与标签色**完全无关**。
 *
 * 两者不可互相替代：来源色是固定的三色，标签色是用户从 8 色板里选的、可随时改。
 * 卡片左侧色条用来源色，徽章用标签色 —— 这样一眼能区分「哪来的」和「归哪类」。
 */
object SourceColors {

    fun of(colors: AppColors, source: SourceApp): Color = when (source) {
        SourceApp.WECHAT -> colors.sourceWeChat
        SourceApp.QQ -> colors.sourceQq
        SourceApp.SMS -> colors.sourceSms
        SourceApp.UNKNOWN -> colors.sourceUnknown
    }
}
