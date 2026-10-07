package dev.mm.wxcj

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

/**
 * 禁止上传「对方正在输入」状态。
 *
 * 原理：微信把输入状态封装成 MMTypingSend.Req，由 modelimple 下的
 * NetScene 通过 doScene() 上报。在 doScene 执行前直接把返回值置为 -1，
 * 原方法体不再执行 —— 请求根本发不出去，对方就收不到输入状态提示。
 *
 * 只影响自己上传的输入状态，不影响接收对方的输入状态。
 */
object DisableTypingStatus {

    private const val TAG = "DisableTypingStatus"

    @Volatile
    var verbose = false

    /** 拦截计数，用于诊断确认 hook 真的在工作 */
    @Volatile
    var blockedCount = 0
        private set

    @Volatile
    private var lastNoticeMs = 0L

    fun install(methods: List<Method>): Int {
        var n = 0
        methods.forEach { method ->
            runCatching { hookOne(method) }
                .onSuccess { n++ }
                .onFailure { Logger.e(TAG, "hook 失败：${method.declaringClass.name}.${method.name}", it) }
        }
        if (n == 0) Logger.w(TAG, "没有任何候选挂载成功")
        return n
    }

    private fun hookOne(method: Method) {
        // 只有返回 int 的 doScene 才能用 -1 短路；万一新版改了签名，宁可不生效也不崩。
        if (method.returnType != Int::class.javaPrimitiveType && method.returnType != Int::class.java) {
            Logger.w(TAG, "doScene 返回类型不是 int（${method.returnType.name}），跳过")
            return
        }

        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // 设置了 result，原方法体会被跳过
                param.result = -1
                val n = ++blockedCount
                if (verbose && n <= 3) {
                    Notifier.notify("禁止输入状态：已拦截第 $n 次上传")
                } else if (verbose) {
                    val now = System.currentTimeMillis()
                    if (now - lastNoticeMs > 30_000) {
                        lastNoticeMs = now
                        Notifier.notify("禁止输入状态：累计拦截 $n 次")
                    }
                }
            }
        })
        Logger.i(TAG, "hook 已安装：${method.declaringClass?.name}.${method.name}")
    }
}
