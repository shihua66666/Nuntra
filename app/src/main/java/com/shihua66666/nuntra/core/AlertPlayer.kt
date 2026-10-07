package com.shihua66666.nuntra.core

import android.content.Context
import android.media.MediaPlayer
import android.media.RingtoneManager

/**
 * 提醒音播放器。
 *
 * 音源优先级（逐级降级，保证「一定有声音」或至少知道为什么没有）：
 *   1. res/raw/alert_priority —— 需求指定的文件名（放 mp3 / wav / ogg 都行）；
 *   2. res/raw/alert_fallback —— 项目内置的兜底提示音，保证没放文件时也能响；
 *   3. 系统默认通知音 —— 最后的兜底。
 *
 * 为什么用 getIdentifier 按名字探测，而不是直接写 R.raw.alert_priority：
 *   直接引用会让「素材文件不存在」变成 aapt 阶段的编译错误，
 *   而本项目的硬性约束是「缺素材也必须能编译通过」。
 *   按名字探测则放与不放都能编过 —— 与 MonoFont 的处理方式保持一致。
 */
object AlertPlayer {

    private const val TAG = "AlertPlayer"

    /** 需求指定的文件名（不含扩展名）。 */
    private const val NAME_PRIMARY = "alert_priority"

    /** 内置兜底音。 */
    private const val NAME_FALLBACK = "alert_fallback"

    /** 音源：内置资源。 */
    const val SOUND_BUILTIN = "builtin"

    /** 音源：系统默认通知音。 */
    const val SOUND_SYSTEM = "system"

    private val lock = Any()

    private var player: MediaPlayer? = null

    private var ringtone: android.media.Ringtone? = null

    /**
     * 播放一次提醒音。
     *
     * @param volumeScale 0..1，作用在本次播放的左右声道上（不改系统音量）
     * @param soundSource [SOUND_BUILTIN] 或 [SOUND_SYSTEM]
     * @return 是否成功发出声音；false 时调用方可据此提示用户
     */
    fun play(context: Context, volumeScale: Float = 1f, soundSource: String = SOUND_BUILTIN): Boolean {
        val app = context.applicationContext
        val scale = volumeScale.coerceIn(0f, 1f)
        return when (soundSource) {
            SOUND_SYSTEM -> playSystem(app, scale)
            else -> playBuiltin(app, scale) || playSystem(app, scale)
        }
    }

    /** 停止当前播放（试听时「停止」按钮用）。 */
    fun stop() {
        synchronized(lock) {
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
            runCatching { ringtone?.stop() }
            ringtone = null
        }
    }

    /** 是否正在播放。 */
    fun isPlaying(): Boolean = synchronized(lock) {
        runCatching { player?.isPlaying == true }.getOrDefault(false)
    }

    private fun playBuiltin(app: Context, scale: Float): Boolean {
        val resId = resolveRawId(app) ?: return false
        return synchronized(lock) {
            runCatching {
                stop()
                val mp = MediaPlayer.create(app, resId)
                if (mp == null) {
                    Logx.w(TAG, "MediaPlayer.create 返回 null，回退系统通知音")
                    false
                } else {
                    mp.setVolume(scale, scale)
                    mp.setOnCompletionListener { finished ->
                        runCatching { finished.release() }
                    }
                    mp.start()
                    player = mp
                    true
                }
            }.getOrElse { tr ->
                Logx.swallow(TAG, "playBuiltin", tr)
                false
            }
        }
    }

    /** 依次探测 alert_priority 与 alert_fallback，都缺就返回 null。 */
    private fun resolveRawId(app: Context): Int? {
        val primary = app.resources.getIdentifier(NAME_PRIMARY, "raw", app.packageName)
        if (primary != 0) return primary
        val fallback = app.resources.getIdentifier(NAME_FALLBACK, "raw", app.packageName)
        if (fallback != 0) return fallback
        Logx.w(
            TAG,
            "未找到 res/raw/" + NAME_PRIMARY + " 或 " + NAME_FALLBACK + "，回退系统通知音",
        )
        return null
    }

    private fun playSystem(app: Context, scale: Float): Boolean = synchronized(lock) {
        runCatching {
            stop()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (uri == null) {
                false
            } else {
                val rt = RingtoneManager.getRingtone(app, uri)
                if (rt == null) {
                    false
                } else {
                    rt.volume = scale
                    rt.play()
                    ringtone = rt
                    true
                }
            }
        }.getOrElse { tr ->
            Logx.swallow(TAG, "playSystem", tr)
            false
        }
    }
}
