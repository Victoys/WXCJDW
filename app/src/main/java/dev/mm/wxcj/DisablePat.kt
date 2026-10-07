package dev.mm.wxcj

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

/**
 * 禁用拍一拍：双击他人头像时不发送拍一拍。
 *
 * 原理：微信在 AvatarDoubleClickListener 里处理双击头像，该回调返回 true 表示
 * 「事件已被消费」。我们在方法执行前直接把返回值置为 true，原方法体不再执行，
 * 拍一拍请求也就发不出去。与 WeKit 的 DisablePat 完全一致。
 */
object DisablePat {

    private const val TAG = "DisablePat"

    fun install(methods: List<Method>): Int {
        var n = 0
        methods.forEach { method ->
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                })
                Logger.i(TAG, "hook 已安装：${method.declaringClass.name}.${method.name}")
            }.onSuccess { n++ }
                .onFailure { Logger.e(TAG, "hook 失败：${method.declaringClass.name}.${method.name}", it) }
        }
        if (n == 0) Logger.w(TAG, "没有任何候选挂载成功")
        return n
    }
}
