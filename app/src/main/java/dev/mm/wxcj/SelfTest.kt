package dev.mm.wxcj

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

/**
 * 注入链路自检。
 *
 * 为什么需要它：之前所有诊断都建立在「功能没生效」这个结果上，但无法区分
 * 下面两种完全不同的故障，导致一直往错的方向改：
 *
 *  - **A. hook 链路本身不通**（反射找不到类 / XposedBridge.hookMethod 抛异常 /
 *    回调从未触发）→ 任何功能都不可能生效，改特征串没用；
 *  - **B. hook 通了但 DexKit 没定位到目标** → 只需修特征串或扫描源。
 *
 * 做法：找一个**类名固定、不混淆、必然会被调用**的方法做 hook。
 * `com.tencent.mm.ui.LauncherUI` 是微信主界面，类名从很早的版本起就没变过
 * （WeKit 也拿它当哨兵类名），`onResume()` 每次回到主界面都会触发。
 *
 * 这一步**完全不依赖 DexKit**，纯粹验证反射 + hook + 回调。
 */
object SelfTest {

    private const val TAG = "SelfTest"

    private const val LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI"

    @Volatile
    var installError: String? = null
        private set

    @Volatile
    var callbackCount = 0
        private set

    /** 尝试 hook；返回 true 表示 hook 调用本身成功（不代表回调已触发）。 */
    fun install(classLoader: ClassLoader): Boolean {
        val method = runCatching { findOnResume(classLoader) }
            .onFailure { installError = "定位失败：${it.message}" }
            .getOrNull()

        if (method == null) {
            Logger.e(TAG, "自检失败：${installError}")
            return false
        }

        return runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    callbackCount++
                    if (callbackCount <= 2) {
                        Logger.i(TAG, "自检回调触发（第 $callbackCount 次）")
                    }
                }
            })
            Logger.i(TAG, "自检 hook 已装到 ${method.declaringClass.name}.${method.name}")
            true
        }.onFailure {
            installError = "hook 抛异常：${it.message}"
            Logger.e(TAG, "自检 hook 失败", it)
        }.getOrDefault(false)
    }

    /** LauncherUI 可能没直接重写 onResume，沿父类找。 */
    private fun findOnResume(classLoader: ClassLoader): Method {
        val start = Class.forName(LAUNCHER_UI, false, classLoader)
        var cursor: Class<*>? = start
        while (cursor != null) {
            cursor.declaredMethods.firstOrNull {
                it.name == "onResume" && it.parameterCount == 0
            }?.let {
                it.isAccessible = true
                return it
            }
            cursor = cursor.superclass
        }
        error("$LAUNCHER_UI 及其父类中没有无参 onResume")
    }
}
