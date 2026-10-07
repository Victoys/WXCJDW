package dev.mm.wxcj

import java.lang.reflect.Method

/**
 * DexKit 返回的是 dex 层面的签名（类名 + 方法名 + 形如 (Ljava/lang/String;)Z 的签名），
 * 需要还原成可 hook 的 java.lang.reflect.Method。
 *
 * 逻辑与 WeKit 的 DexMethodDescriptor 一致：先在本类声明的方法里按「名字 + 签名」精确匹配，
 * 找不到再沿父类向上找（微信大量 hook 点都定义在父类里）。
 */
object DexSig {

    /** JVM 类型描述符：int -> I，String -> Ljava/lang/String;，int[] -> [I */
    fun typeSig(type: Class<*>): String {
        if (type.isPrimitive) return when (type) {
            java.lang.Integer.TYPE -> "I"
            java.lang.Void.TYPE -> "V"
            java.lang.Boolean.TYPE -> "Z"
            java.lang.Character.TYPE -> "C"
            java.lang.Byte.TYPE -> "B"
            java.lang.Short.TYPE -> "S"
            java.lang.Float.TYPE -> "F"
            java.lang.Long.TYPE -> "J"
            java.lang.Double.TYPE -> "D"
            else -> error("未知原始类型：${type.name}")
        }
        if (type.isArray) return "[${typeSig(type.componentType!!)}"
        return "L${type.name.replace('.', '/')};"
    }

    fun methodSig(method: Method): String = buildString {
        append('(')
        method.parameterTypes.forEach { append(typeSig(it)) }
        append(')')
        append(typeSig(method.returnType))
    }

    /**
     * 按 DexKit 结果定位 Method。
     * @param className DexKit 的 className（点分或斜杠分都可能，这里做兼容）
     */
    fun find(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        sign: String,
    ): Method? {
        val normalized = className.replace('/', '.').removePrefix("L").removeSuffix(";")
        val start = runCatching { Class.forName(normalized, false, classLoader) }
            .onFailure { Logger.w("DexSig", "类加载失败：$normalized") }
            .getOrNull() ?: return null

        var cursor: Class<*>? = start
        while (cursor != null) {
            val hit = cursor.declaredMethods.firstOrNull {
                it.name == methodName && methodSig(it) == sign
            }
            if (hit != null) {
                hit.isAccessible = true
                return hit
            }
            cursor = cursor.superclass
        }
        Logger.w("DexSig", "未能在 $normalized 及其父类中找到 $methodName$sign")
        return null
    }

    /** 把 Method 序列化成缓存用的字符串：类名|方法名|签名 */
    fun encode(method: Method): String =
        "${method.declaringClass.name}|${method.name}|${methodSig(method)}"

    fun decode(classLoader: ClassLoader, value: String): Method? {
        val parts = value.split('|')
        if (parts.size != 3) return null
        return runCatching { find(classLoader, parts[0], parts[1], parts[2]) }
            .onFailure { Logger.w("DexSig", "缓存还原失败：$value") }
            .getOrNull()
    }
}
