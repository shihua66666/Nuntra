package com.shihua66666.nuntra.model

/**
 * 监控范围快照（四个分闸）。
 *
 * 用不可变快照而不是让过滤器反复读 DataStore：
 * 监听回调在每条通知上都会执行，不能每次都做一次磁盘读取。
 * 监听服务持有最新快照，设置变更时更新。
 *
 * 默认值与需求一致：微信/QQ 开，企业微信/TIM 关。
 */
data class MonitorScope(
    val wechat: Boolean = true,
    val qq: Boolean = true,
    val weCom: Boolean = false,
    val tim: Boolean = false,
) {
    val anyEnabled: Boolean get() = wechat || qq || weCom || tim

    companion object {
        val ALL_ON = MonitorScope(wechat = true, qq = true, weCom = true, tim = true)
    }
}
