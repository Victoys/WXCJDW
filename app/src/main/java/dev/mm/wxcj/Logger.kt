package dev.mm.wxcj

import de.robv.android.xposed.XposedBridge

/** 统一走 XposedBridge.log，可在 LSPosed 日志里按 Wxcj 过滤。 */
object Logger {
    private const val TAG = "Wxcj"

    fun i(tag: String, msg: String) = XposedBridge.log("[$TAG/$tag] $msg")
    // 与 e 保持一致的重载：w 也接受可选 Throwable，避免调用时传三参导致编译失败
    fun w(tag: String, msg: String, tr: Throwable? = null) {
        XposedBridge.log("[$TAG/$tag] WARN $msg")
        tr?.let { XposedBridge.log(it) }
    }
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        XposedBridge.log("[$TAG/$tag] ERROR $msg")
        tr?.let { XposedBridge.log(it) }
    }
}
