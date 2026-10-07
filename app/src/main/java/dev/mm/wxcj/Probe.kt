package dev.mm.wxcj

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicInteger

/**
 * hook 机制对照探针。
 *
 * ## 为什么要它
 *
 * v1.6 的自检显示「hook 链路正常」，但三个业务 hook 的调用计数全是 0。
 * 「hookMethod 没抛异常」不等于「hook 真的生效」—— 这是 Xposed 模块最典型的静默失败。
 * 光看「装上了」永远无法区分下面几种情况：
 *
 *  1. hook 机制本身没工作（框架/作用域/进程不对）
 *  2. `lpparam.classLoader` 拿到的类 ≠ 运行时实际的类（微信 Tinker 会换 ClassLoader）
 *  3. 定位到了错误的方法（特征串命中多个，取错了）
 *
 * ## 做法
 *
 * 三组对照，全部带独立计数器，20 秒后一起报告：
 *
 *  - **A**：`XposedHelpers.findAndHookMethod("android.app.Activity", "onResume")`
 *    —— 已知有效的方式（Notifier 用它，浮层能弹出来就是证据）。作为基准线。
 *  - **B**：`XposedHelpers.findAndHookMethod("com.tencent.mm.ui.LauncherUI", "onResume")`
 *    —— 验证这个类名在当前微信版本还是不是真正的主界面。
 *  - **C**：用**反射找 Method + XposedBridge.hookMethod** 的方式 hook 同一个
 *    `android.app.Activity.onResume` —— 这正是所有业务 hook 使用的方式。
 *
 * 判读：
 *  - A>0 且 C=0 → `XposedBridge.hookMethod(反射来的Method)` 这种方式有问题（改 A 的方式）
 *  - A>0 且 B=0 → LauncherUI 类名失效了（只影响探针，不影响业务）
 *  - A=0 → hook 机制整体没工作
 */
object Probe {

    private const val TAG = "Probe"

    private const val LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI"

    private val countA = AtomicInteger(0)   // XposedHelpers + android.app.Activity
    private val countB = AtomicInteger(0)   // XposedHelpers + LauncherUI
    private val countC = AtomicInteger(0)   // 反射 Method + XposedBridge.hookMethod

    /** 安装三组对照。返回失败原因列表（空 = 全部装上了）。 */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam, realClassLoader: ClassLoader): List<String> {
        val errors = ArrayList<String>()

        // ---- A：基准 ----
        val hookA = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                countA.incrementAndGet()
            }
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity", lpparam.classLoader, "onResume", hookA,
            )
        }.onFailure { errors += "A:${it.message}" }

        // ---- B：LauncherUI 是否还是主界面 ----
        runCatching {
            val hookB = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    countB.incrementAndGet()
                }
            }
            XposedHelpers.findAndHookMethod(
                LAUNCHER_UI, lpparam.classLoader, "onResume", hookB,
            )
        }.onFailure { errors += "B:${it.message}" }

        // ---- C：反射 Method + hookMethod（业务 hook 用的方式）----
        runCatching {
            val m = findOnResume(realClassLoader)
            val hookC = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    countC.incrementAndGet()
                }
            }
            XposedBridge.hookMethod(m, hookC)
            Logger.i(TAG, "C 组 hook 到 ${m.declaringClass.name}.${m.name}")
        }.onFailure { errors += "C:${it.message}" }

        Logger.i(TAG, "三组对照已安装，失败项：$errors")
        return errors
    }

    fun report(): String = "探针 A(Activity)=${countA.get()} B(LauncherUI)=${countB.get()} C(反射hookMethod)=${countC.get()}"

    private fun findOnResume(cl: ClassLoader): java.lang.reflect.Method {
        var cursor: Class<*>? = Class.forName("android.app.Activity", false, cl)
        while (cursor != null) {
            cursor.declaredMethods.firstOrNull {
                it.name == "onResume" && it.parameterCount == 0
            }?.let { it.isAccessible = true; return it }
            cursor = cursor.superclass
        }
        error("android.app.Activity 中没有无参 onResume")
    }
}
