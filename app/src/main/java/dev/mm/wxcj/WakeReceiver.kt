package dev.mm.wxcj

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 只为「把模块进程拉起来」而存在。
 *
 * 模块的配置由 [PrefsProvider] 提供，而 Provider 只有在**模块进程活着**时才可用。
 * 微信冷启动时模块进程通常还没起来，直接 query 会拿到 null，导致所有配置回退默认值
 * （表现：功能全部失效，且时好时坏 —— 取决于那一刻模块进程是否还活着）。
 *
 * 微信进程在读配置前会发一条**显式广播**到此 Receiver：
 *
 * ```
 * Intent("dev.mm.wxcj.WAKE").setPackage("dev.mm.wxcj")
 *     .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
 * ```
 *
 * 显式广播不受 Android 8+ 的隐式广播后台限制，配合 FLAG_INCLUDE_STOPPED_PACKAGES
 * 连被强制停止的包也能拉起，是少数还能跨进程唤醒应用的手段之一。
 *
 * 收到后什么都不用做：系统已经启动了模块进程，Provider 随即可用。
 */
class WakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Logger.i(TAG, "收到唤醒广播：${intent.action}")
    }

    companion object {
        private const val TAG = "Wake"
    }
}
