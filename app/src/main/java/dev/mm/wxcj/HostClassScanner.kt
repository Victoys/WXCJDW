package dev.mm.wxcj

import android.content.Context
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.io.File
import java.util.Enumeration
import java.util.LinkedHashSet

/**
 * 在**宿主进程里动态枚举类名**，用来替代「写死候选类名」。
 *
 * ## 为什么需要它
 *
 * v3.0 的诊断显示：6 个写死的候选类（`com.tencent.mm.storage.MsgInfo`、
 * `storage.bi`、`g.c.eo`、`g.c.dy`、`g.c.ei` 等）**全部返回「无此类」**。
 *
 * 微信 8.0.78 显然把这个类挪到了别处（或整个改了名）。继续往名单里加名字是猜谜，
 * 而且永远追不上版本变化。正确做法是**去宿主 dex 里把类名枚举出来**，
 * 再按「有没有 setContent(String) / talker / msgSvrId」打分挑出真正的消息存储类。
 *
 * ## 两条枚举路径
 *
 *  1. `BaseDexClassLoader.pathList.dexElements[].dexFile.entries()` —— 覆盖 Tinker
 *     热修复后的真实 ClassLoader，最准。
 *  2. `ApplicationInfo.sourceDir + splitSourceDirs` 用 `DexFile.loadDex` 打开 ——
 *     微信是 split APK，classes 可能分散在多个 dex 里。
 *
 * 两条都包在 runCatching 里：任何一条失败都只是降级，不会崩。
 */
object HostClassScanner {

    private const val TAG = "HostClassScanner"

    /** 打分上限：setContent + talker + msgSvrId 三项全中 */
    private const val SCORE_FULL = 3

    /** 最多加载多少个候选类做打分，防止极端情况卡住启动 */
    private const val MAX_LOAD = 600

    data class Result(val clazz: Class<*>?, val reason: String)

    /** 匹配 `zip file "/path/to/x.apk"` */
    private val DEX_PATH_RE = Regex("""(?:zip\s+file|dex\s+file)\s+"([^"]+)""")

    /**
     * 找出宿主的「消息存储类」。
     *
     * @return [Result]，[Result.reason] 可直接显示给用户，便于远程定位。
     */
    fun findMessageInfoClass(context: Context?, classLoader: ClassLoader): Result {
        val names = enumerate(context, classLoader)
        if (names.isEmpty()) {
            return Result(null, "枚举类名失败（ClassLoader 结构不支持且源 APK 不可读）")
        }

        val candidates = names.filter { isPlausible(it) }.take(MAX_LOAD)
        Logger.i(TAG, "枚举到 ${names.size} 个类，其中 ${candidates.size} 个看起来像消息存储类")

        var fallback: Class<*>? = null
        var fallbackName = ""
        val tried = ArrayList<String>()

        for (name in candidates) {
            val c = runCatching { Class.forName(name, false, classLoader) }
                .getOrNull() ?: continue
            val score = score(c)
            tried += "$name=$score"
            when {
                score >= SCORE_FULL ->
                    return Result(c, "命中 $name（content字段+talker+msgSvrId）")
                score == 2 && fallback == null -> {
                    fallback = c
                    fallbackName = name
                }
            }
        }

        if (fallback != null) {
            return Result(fallback, "部分命中 $fallbackName（只有 2/3 项）")
        }
        return Result(null, "扫了 ${candidates.size} 个候选仍未命中：${tried.take(12).joinToString("、")}")
    }

    // ── 枚举类名 ───────────────────────────────────────────────────────────

    private fun enumerate(context: Context?, classLoader: ClassLoader): List<String> {
        val out = LinkedHashSet<String>()
        runCatching { fromClassLoader(classLoader, out) }
        // 从 ClassLoader 的 toString 里解析 dex 路径：这条**不依赖 pathList 反射**，
        // 且在微信打过 Tinker 补丁时能拿到 patch 的真实路径（反射那条拿不到）。
        runCatching { fromClassLoaderText(classLoader, out) }
        if (context != null) runCatching { fromApk(context, out) }
        return out.toList()
    }

    /**
     * 从 `ClassLoader.toString()` 里抠出 dex 路径。
     *
     * 形如：
     * `dalvik.system.DelegateLastClassLoader[DexPathList[[zip file "/data/user/0/
     *  com.tencent.mm/tinker/patch-0587a26a/dex/tinker_classN.apk"], ...`
     *
     * 这么做的原因：微信热修复（Tinker）后 `baseContext.classLoader` 会变成
     * `DelegateLastClassLoader`，**没有 `pathList` 字段**可反射（8.0.78 实测），
     * 但补丁里的类又必须能枚举到 —— 而 `sourceDir` 只指向 base.apk，会漏掉它们。
     */
    private fun fromClassLoaderText(classLoader: ClassLoader, out: LinkedHashSet<String>) {
        val found = ArrayList<String>()
        var cl: ClassLoader? = classLoader
        var depth = 0
        while (cl != null && depth < 6) {
            for (m in DEX_PATH_RE.findAll(cl.toString())) {
                found += m.groupValues[1]
            }
            cl = cl.parent
            depth++
        }
        for (p in found.distinct()) {
            loadDex(p)?.let { collect(it, out) }
            Logger.i(TAG, "ClassLoader 文本解析到 dex：$p")
        }
    }

    /** 沿 ClassLoader 链找 BaseDexClassLoader，再挖 pathList → dexElements → dexFile。 */
    private fun fromClassLoader(classLoader: ClassLoader, out: LinkedHashSet<String>) {
        var cl: ClassLoader? = classLoader
        var depth = 0
        while (cl != null && depth < 6) {
            if (cl is BaseDexClassLoader) {
                val pathList = readField(cl, "pathList") ?: return
                val elements = readField(pathList, "dexElements") as? Array<*> ?: return
                for (e in elements) {
                    if (e == null) continue
                    val dex = readField(e, "dexFile") as? DexFile
                        ?: (readField(e, "path") as? String)?.let { loadDex(it) }
                        ?: continue
                    collect(dex, out)
                }
                return
            }
            cl = cl.parent
            depth++
        }
    }

    /** 兜底：直接读宿主 APK 与 split APK。微信是 split APK，classes 可能分散。 */
    private fun fromApk(context: Context, out: LinkedHashSet<String>) {
        val info = context.applicationInfo ?: return
        val paths = ArrayList<String>()
        info.sourceDir?.let { paths += it }
        info.splitSourceDirs?.let { paths += it }
        // Tinker 补丁目录：ClassLoader 文本解析失败时的再兜底
        runCatching {
            File("/data/user/0/${context.packageName}/tinker").listFiles()?.forEach { patch ->
                File(patch, "dex").listFiles()
                    ?.filter { it.name.endsWith(".apk") || it.name.endsWith(".jar") }
                    ?.forEach { paths += it.absolutePath }
            }
        }
        for (p in paths.distinct()) loadDex(p)?.let { collect(it, out) }
    }

    private fun loadDex(path: String): DexFile? =
        runCatching { DexFile.loadDex(path, null, 0) }
            .onFailure { Logger.w(TAG, "打开 dex 失败 $path：${it.message}") }
            .getOrNull()

    @Suppress("DEPRECATION")
    private fun collect(dex: DexFile, out: LinkedHashSet<String>) {
        val entries: Enumeration<String> = runCatching { dex.entries() }.getOrNull() ?: return
        while (entries.hasMoreElements()) {
            runCatching { out.add(entries.nextElement()) }
        }
    }

    private fun readField(target: Any, name: String): Any? =
        runCatching {
            val f = target.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.get(target)
        }.onFailure { Logger.w(TAG, "读字段 $name 失败：${it.message}") }.getOrNull()

    // ── 打分 ───────────────────────────────────────────────────────────────

    /**
     * 只保留「像消息存储类」的名字，避免把几万个类全加载一遍。
     *
     * 微信的存储类历史上在 `com.tencent.mm.storage.*`，代码生成基类在
     * `com.tencent.mm.g.c.*`（名字被混淆成 eo / dy / ei 这种两三个字母）。
     * 另外兜底匹配名字里含 msginfo 的。
     */
    private fun isPlausible(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.startsWith("com.tencent.mm.g.c.")) return true
        if (lower.startsWith("com.tencent.mm.storage.")) return true
        return lower.contains("msginfo") || lower.contains("msg_info")
    }

    /** setContent(String) + talker + msgSvrId，三项各 1 分。 */
    /**
     * 打分：content **字段** + talker + msgSvrId。
     *
     * 注意用「content 字段」而不是 `setContent` 方法 —— 8.0.78 上消息存储类被混淆成
     * `com.tencent.mm.storage.e9`，**方法名也一起被混淆了，根本没有 setContent**，
     * 但数据库列名映射的字段（如 `field_content`）通常保留。
     */
    private fun score(c: Class<*>): Int {
        var s = 0
        if (findStringField(c, "content") != null) s++
        if (hasMemberNamed(c, "talker", String::class.java)) s++
        if (hasLongMemberNamed(c, "svrid")) s++
        return s
    }

    /** 找名字含 keyword 的 String 字段（沿继承链）。 */
    fun findStringField(c: Class<*>, keyword: String): Field? {
        var cur: Class<*>? = c
        while (cur != null) {
            cur.declaredFields.firstOrNull {
                it.type == String::class.java && it.name.contains(keyword, ignoreCase = true)
            }?.let { return it }
            cur = cur.superclass
        }
        return null
    }

    /**
     * 找所有「单 String 参数的 setter」（沿继承链，去重，最多 [MAX_SETTER] 个）。
     *
     * 因为方法名被混淆，无法按名字挑出内容 setter，只能**全都挂上**，
     * 回调里再用 content 字段的值做校验（见 MsgSnapshots）。
     */
    fun findStringSetters(c: Class<*>): List<Method> {
        val out = LinkedHashMap<String, Method>()
        var cur: Class<*>? = c
        while (cur != null && out.size < MAX_SETTER) {
            for (m in cur.declaredMethods) {
                if (m.parameterCount != 1) continue
                if (m.parameterTypes[0] != String::class.java) continue
                if (m.returnType != Void.TYPE && m.name != "setContent") continue
                out["${cur.name}#${m.name}"] = m
                if (out.size >= MAX_SETTER) break
            }
            cur = cur.superclass
        }
        return out.values.toList()
    }

    /** 单类最多挂多少个 setter hook，防止方法过多拖慢消息入库。 */
    private const val MAX_SETTER = 10

    /** 方法名或字段名包含 keyword，且类型是 [type]。 */
    private fun hasMemberNamed(c: Class<*>, keyword: String, type: Class<*>): Boolean {
        var cur: Class<*>? = c
        while (cur != null) {
            if (cur.declaredMethods.any {
                it.parameterCount == 0 && it.returnType == type &&
                    it.name.contains(keyword, ignoreCase = true)
            }) return true
            if (cur.declaredFields.any {
                it.type == type && it.name.contains(keyword, ignoreCase = true)
            }) return true
            cur = cur.superclass
        }
        return false
    }

    private fun hasLongMemberNamed(c: Class<*>, keyword: String): Boolean {
        var cur: Class<*>? = c
        while (cur != null) {
            if (cur.declaredMethods.any {
                it.parameterCount == 0 &&
                    (it.returnType == java.lang.Long.TYPE || it.returnType == java.lang.Long::class.java) &&
                    it.name.contains(keyword, ignoreCase = true)
            }) return true
            if (cur.declaredFields.any {
                (it.type == java.lang.Long.TYPE || it.type == java.lang.Long::class.java) &&
                    it.name.contains(keyword, ignoreCase = true)
            }) return true
            cur = cur.superclass
        }
        return false
    }
}
