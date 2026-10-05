package com.shihua66666.nuntra.ui.theme

import android.content.Context
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat
import com.shihua66666.nuntra.core.Logx

/**
 * 等宽字体加载：**缺文件必须回退，不允许因此崩溃或阻塞构建**。
 *
 * 为什么用 getIdentifier 而不是 R.font.xxx：
 *  · 直接引用 R.font.ibm_plex_mono_regular 时，如果文件不存在，
 *    编译期就会失败（aapt 阶段），整个工程无法 Sync —— 这是必须避免的。
 *  · 用资源名探测可以在「没有字体文件」的情况下正常编译，运行时回退系统等宽。
 *
 * 字体文件约定名：res/font/ibm_plex_mono_regular.ttf
 */
object MonoFont {

    private const val RES_NAME = "ibm_plex_mono_regular"

    /**
     * 探测并加载。任何异常都返回 [FontFamily.Monospace]。
     *
     * 结果刻意不缓存：调用点仅两处（Activity 与 OverlayService 各一次），
     * 且需要在「用户中途放入字体」后重启也能生效。
     */
    fun load(context: Context): FontFamily {
        return runCatching {
            val resId = context.resources.getIdentifier(RES_NAME, "font", context.packageName)
            if (resId == 0) {
                Logx.i("MonoFont", "未找到 res/font/" + RES_NAME + "，回退系统等宽字体（不影响功能）")
                return@runCatching FontFamily.Monospace
            }
            val typeface = ResourcesCompat.getFont(context, resId)
            if (typeface == null) {
                Logx.w("MonoFont", "字体资源存在但加载失败，回退系统等宽字体")
                return@runCatching FontFamily.Monospace
            }
            Logx.i("MonoFont", "已加载 IBM Plex Mono")
            // FontFamily(typeface) 是长期稳定重载；不用 Font(typeface) 构造，
            // 后者在不同 Compose 版本的参数签名有过变化，属于可避免的 Sync 风险。
            FontFamily(typeface)
        }.getOrElse { tr ->
            Logx.swallow("MonoFont", "load", tr)
            FontFamily.Monospace
        }
    }

    /**
     * 给某个字重构造 FontFamily。
     *
     * 只有一个 Regular 字形文件，粗体由系统合成；
     * 不为 Medium/Bold 再声明 Font 条目，否则缺字重时会命中不到而报错。
     */
    fun withWeight(base: FontFamily, weight: FontWeight): FontFamily = base
}
