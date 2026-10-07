package dev.mm.wxcj

import android.content.Context
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method

/**
 * 解析结果。
 * [scanned] = true 表示本次真的扫了 dex（而不是命中缓存），调用方据此决定是否提示用户。
 */
data class ResolveResult(
    /**
     * 每个目标对应的**全部候选方法**。
     *
     * 为什么是 List 而不是单个：DexKit 按特征串匹配经常命中多个，WeKit 遇到多个会
     * 直接抛异常（allowMultiple=false），而我们无法实机验证该选哪个 —— 于是全部 hook。
     * 这些方法里通常只有一个会被真正调用，全挂上对正确性无影响，却能极大提高命中率。
     */
    val targets: Map<String, List<Method>>,
    val scanned: Boolean,
    val elapsedMs: Long,
    /** 本次没能定位到的目标 key；用于提示与日志诊断 */
    val missing: List<String> = emptyList(),
    /**
     * 每个目标匹配到的候选数量。
     *
     * **这是定位「静默取错方法」的关键诊断**：WeKit 在匹配到多个时会直接抛异常
     * （`allowMultiple=false`），而我之前静默取 `firstOrNull()` —— 如果特征串不够独特，
     * 第一个很可能不是真正的 hook 点，表现为「hook 装上了但功能不生效」。
     */
    val candidateCounts: Map<String, Int> = emptyMap(),
)

/**
 * 用 DexKit 在微信 APK 里按「特征字符串」定位被混淆的类/方法。
 *
 * 微信每个版本都会重新混淆类名与方法名，所以一律不能硬编码。下面所有特征串
 * 都沿用 WeKit（GPL-3.0）同名功能的取值，在 8.0.65 – 8.0.78 区间验证过。
 *
 * 本模块服务 4 个功能，共 7 个定位目标，全部是「hook 返回值」型，
 * 不涉及任何菜单/UI 注入 —— 微信内部怎么重构都相对稳。
 * （虚拟定位占其中 3 个：微信的定位回调有好几套，见 [LOCATION_KEYS]。）
 *
 * 解析结果会缓存（见 DexCache），只在微信升级后重扫。
 * 任何一项没找到只是该项为 null，对应功能自动跳过，不会影响其他功能，
 * 也不会拖慢或阻断微信启动。
 */
object DexTargets {

    private const val TAG = "Dex"

    /** 单个目标最多 hook 几个候选，避免特征串过于泛化时挂上几十个方法拖慢微信 */
    private const val MAX_CANDIDATES = 4

    /** 目标总数（2 个特殊目标 + METHOD_SPECS）。改动列表时记得同步这个值。 */
    const val TOTAL = 7

    /** 目标 key -> 中文名，用于把「哪个功能没定位到」直接显示给用户。 */
    fun labelOf(key: String): String = when (key) {
        K_XML_PARSER -> "防撤回"
        K_DO_REVOKE_MSG -> "防撤回(doRevokeMsg)"
        K_TYPING_DO_SCENE -> "禁止输入状态"
        K_PAT_DOUBLE_CLICK -> "禁用拍一拍"
        // 三个定位回调同属「虚拟定位」这一个功能，提示时会被去重成一条
        K_LOC_LISTENER, K_LOC_LISTENER_WGS84, K_LOC_DEFAULT_MANAGER -> "虚拟定位"
        else -> key
    }

    // ---- 目标 key ----
    const val K_XML_PARSER = "xmlParser"                     // 防撤回：改写 sysmsg
    /** 防撤回第二条路径：直接取消微信的撤回处理方法（WAuxiliary 的做法） */
    const val K_DO_REVOKE_MSG = "doRevokeMsg"
    const val K_TYPING_DO_SCENE = "typingDoScene"            // 禁止上传正在输入
    const val K_PAT_DOUBLE_CLICK = "patDoubleClick"          // 禁用拍一拍

    /** 虚拟定位：微信自己的定位回调（腾讯定位 SDK 的结果对象从这里进来） */
    const val K_LOC_LISTENER = "locListener"
    const val K_LOC_LISTENER_WGS84 = "locListenerWgs84"
    const val K_LOC_DEFAULT_MANAGER = "locDefaultManager"

    /** 虚拟定位用到的全部目标，HookEntry 会把它们的候选合并起来一起装 */
    val LOCATION_KEYS = listOf(K_LOC_LISTENER, K_LOC_LISTENER_WGS84, K_LOC_DEFAULT_MANAGER)

    // ---- 特征串 ----

    private const val XML_PARSER_PKG = "com.tencent.mm.sdk.platformtools"
    private val XML_PARSER_STRINGS = arrayOf("MicroMsg.SDK.XmlParser", "[ %s ]")

    /**
     * WAuxiliary `AntiRevoke1Hook` 的 DexKit 指纹，源码确认过的真实特征串：
     * `usingEqStrings("doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s")`。
     * 该方法签名约 (String, long, p0, String, String, String)，参数里带 talker 与 msgSvrId。
     */
    private val DO_REVOKE_STRINGS = arrayOf("doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s")

    private const val TYPING_PKG = "com.tencent.mm.modelsimple"
    private val TYPING_CLASS_STRINGS = arrayOf(
        "null cannot be cast to non-null type com.tencent.mm.protocal.MMTypingSend.Req",
        "autoAuth",
    )

    private data class Spec(
        val key: String,
        val pkg: String? = null,
        val methodName: String? = null,
        /** 为空表示只按方法名/包名匹配，不加字符串条件 */
        val strings: Array<String> = emptyArray(),
    )

    /** 本次解析中各目标的候选数量（诊断用） */
    private val candidateCounts = LinkedHashMap<String, Int>()

    /**
     * 虚拟定位的三个指纹，取值与 WeKit 的 `FakeLocation` 完全一致：
     * 方法名 `onLocationChanged` + 各自日志里的 tag。
     *
     * 微信的几套定位监听（普通监听 / WGS84 监听 / 默认管理器回调）分开匹配，
     * 能命中几个就 hook 几个 —— 多选并不会出错，多覆盖一条路径而已。
     */
    private val LOC_LISTENER_STRINGS = arrayOf("MicroMsg.SLocationListener")
    private val LOC_LISTENER_WGS84_STRINGS = arrayOf("MicroMsg.SLocationListenerWgs84")
    private val LOC_DEFAULT_MANAGER_STRINGS = arrayOf(
        "MicroMsg.DefaultTencentLocationManager",
        "[mlocationListener]error:%d, reason:%s",
    )

    private val METHOD_SPECS = listOf(
        Spec(K_PAT_DOUBLE_CLICK, strings = arrayOf("MicroMsg.AvatarDoubleClickListener", "onDoubleClick: %s")),
        Spec(K_DO_REVOKE_MSG, strings = DO_REVOKE_STRINGS),
        Spec(K_LOC_LISTENER, methodName = "onLocationChanged", strings = LOC_LISTENER_STRINGS),
        Spec(K_LOC_LISTENER_WGS84, methodName = "onLocationChanged", strings = LOC_LISTENER_WGS84_STRINGS),
        Spec(K_LOC_DEFAULT_MANAGER, methodName = "onLocationChanged", strings = LOC_DEFAULT_MANAGER_STRINGS),
    )

    /** 全部目标：缓存判断与「缺哪些」的诊断都以此为准，避免漏掉或重复统计 */
    private val ALL_KEYS = METHOD_SPECS.map { it.key } + listOf(K_XML_PARSER, K_TYPING_DO_SCENE)

    /** 解析全部目标，返回 key -> Method（未找到的不出现在 map 里）。 */
    fun resolve(
        lpparam: XC_LoadPackage.LoadPackageParam,
        context: Context?,
        rescanToken: Int = 0,
        /**
         * 实际承载微信业务类的 ClassLoader。默认 lpparam.classLoader，
         * 但微信有 Tinker，建议传 application.baseContext.classLoader（WeKit 的做法）。
         */
        realClassLoader: ClassLoader = lpparam.classLoader,
    ): ResolveResult {
        val result = LinkedHashMap<String, List<Method>>()
        candidateCounts.clear()
        val classLoader = realClassLoader
        val hostVersion = hostVersion(context)

        // 1) 先试缓存
        val cache = context?.let { DexCache.load(it, hostVersion, rescanToken) }
        if (cache != null) {
            cache.forEach { (key, descriptor) ->
                DexSig.decode(classLoader, descriptor)?.let { result[key] = listOf(it) }
            }
            Logger.i(TAG, "缓存命中 ${result.size}/${cache.size} 项（宿主版本 $hostVersion）")
        }

        val missing = ALL_KEYS.filter { it !in result }

        // 缓存全命中时根本不需要加载 libdexkit.so，也不扫 dex —— 这是不拖慢启动的关键
        if (missing.isEmpty()) return ResolveResult(result, false, 0)

        // 2) 缺的再用 DexKit 扫
        if (!NativeLoader.ensureDexKit(context)) {
            Logger.e(TAG, "libdexkit.so 不可用，未缓存的目标无法解析：$missing")
            return ResolveResult(result, false, 0, missing)
        }

        val bridge = openBridge(lpparam, classLoader)
        if (bridge == null) {
            Logger.e(TAG, "DexKit 无法初始化，未缓存的目标无法解析：$missing")
            return ResolveResult(result, false, 0, missing)
        }

        val started = System.currentTimeMillis()
        try {
            bridge.use {
                if (K_XML_PARSER in missing) {
                    findXmlParser(it, classLoader).takeIf { l -> l.isNotEmpty() }
                        ?.let { result[K_XML_PARSER] = it }
                }
                if (K_TYPING_DO_SCENE in missing) {
                    findTypingDoScene(it, classLoader).takeIf { l -> l.isNotEmpty() }
                        ?.let { result[K_TYPING_DO_SCENE] = it }
                }
                for (spec in METHOD_SPECS) {
                    if (spec.key !in missing) continue
                    findMethodByStrings(it, classLoader, spec).takeIf { l -> l.isNotEmpty() }
                        ?.let { result[spec.key] = it }
                }
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "DexKit 解析失败", t)
        }
        val elapsed = System.currentTimeMillis() - started
        Logger.i(TAG, "DexKit 解析耗时 $elapsed ms，命中 ${result.size} 项")

        // 3) 写回缓存
        if (context != null && result.isNotEmpty()) {
            val entries = LinkedHashMap<String, String>()
            result.forEach { (key, methods) -> methods.firstOrNull()?.let { entries[key] = DexSig.encode(it) } }
            DexCache.save(context, hostVersion, entries, rescanToken)
        }
        val stillMissing = ALL_KEYS.filter { it !in result }
        if (stillMissing.isNotEmpty()) Logger.w(TAG, "以下目标未定位到：$stillMissing")
        return ResolveResult(result, true, elapsed, stillMissing, LinkedHashMap(candidateCounts))
    }

    /**
     * 打开 DexKit。
     *
     * **必须用宿主 ClassLoader，不能用 appInfo.sourceDir**：微信是 split APK（且可能被
     * 重打包框架包一层外壳），sourceDir 指向的可能只是个 loader-only 的外壳，真正的
     * dex 在其他 split 里 —— 只扫 base APK 会大面积找不到目标。这也是 WeKit 的做法：
     * `DexKitBridge.create(ClassLoaders.HOST, true)`。
     *
     * 按可靠性依次尝试，任一成功即用。
     */
    private fun openBridge(lpparam: XC_LoadPackage.LoadPackageParam, cl: ClassLoader): DexKitBridge? {
        runCatching { DexKitBridge.create(cl, true) }
            .onFailure { Logger.w(TAG, "create(classLoader, true) 失败：${it.message}") }
            .getOrNull()?.let {
                Logger.i(TAG, "DexKit 就绪：ClassLoader 模式（cookie）")
                return it
            }

        runCatching { DexKitBridge.create(cl, false) }
            .onFailure { Logger.w(TAG, "create(classLoader, false) 失败：${it.message}") }
            .getOrNull()?.let {
                Logger.i(TAG, "DexKit 就绪：ClassLoader 模式（无 cookie）")
                return it
            }

        val apkPath = lpparam.appInfo?.sourceDir
        if (apkPath != null) {
            runCatching { DexKitBridge.create(apkPath) }
                .onFailure { Logger.w(TAG, "create(apkPath) 失败：${it.message}") }
                .getOrNull()?.let {
                    Logger.w(TAG, "DexKit 就绪：APK 路径模式（可能漏掉 split 中的目标）")
                    return it
                }
        }
        return null
    }

    private fun hostVersion(context: Context?): Int {
        if (context == null) return -1
        return runCatching { context.packageManager.getPackageInfo(HookEntry.WECHAT_PACKAGE, 0).versionCode }
            .onFailure { Logger.w(TAG, "读取宿主版本失败：${it.message}") }
            .getOrDefault(-1)
    }

    // ---- 逐个目标 ----

    /**
     * 定位 XmlParser 的解析方法。
     *
     * **必须是 findMethod 而不是 findClass**：WeKit 用的是 `dexMethod`
     * （即 `dexKit.findMethod { searchPackages(...); matcher { usingEqStrings(...) } }`）。
     * 先 findClass 再在类里挑「静态 + 返回 Map」的方法，很容易挑错或挑不到。
     */
    private fun findXmlParser(bridge: DexKitBridge, cl: ClassLoader): List<Method> {
        val all = runCatching {
            bridge.findMethod {
                searchPackages(XML_PARSER_PKG)
                matcher { usingEqStrings(*XML_PARSER_STRINGS) }
            }
        }.onFailure { Logger.e(TAG, "搜索 XmlParser 失败", it) }.getOrNull() ?: return emptyList()

        candidateCounts[K_XML_PARSER] = all.size
        if (all.size > 1) {
            Logger.w(TAG, "XmlParser 匹配到 ${all.size} 个候选，全部 hook：" +
                all.joinToString { "${it.className}->${it.methodName}${it.methodSign}" })
        }

        val out = LinkedHashMap<String, Method>()
        for (data in all.take(MAX_CANDIDATES)) {
            DexSig.find(cl, data.className, data.methodName, data.methodSign)
                ?.let { out["${data.className}->${data.methodName}${data.methodSign}"] = it }
        }
        if (out.isEmpty()) Logger.w(TAG, "XmlParser 有 ${all.size} 个候选但反射还原全部失败")
        else Logger.i(TAG, "XmlParser 候选 ${all.size} 个，可 hook ${out.size} 个：${out.keys.joinToString()}")
        return out.values.toList()
    }

    /** MMTypingSend 的 NetScene：doScene() 上报输入状态。 */
    private fun findTypingDoScene(bridge: DexKitBridge, cl: ClassLoader): List<Method> {
        val clazz = findClassByStrings(bridge, cl, TYPING_PKG, TYPING_CLASS_STRINGS)
            ?: run {
                Logger.w(TAG, "未找到 Typing 请求类，禁止输入状态不可用")
                return emptyList()
            }
        val all = findMethodInHierarchyList(clazz) { it.name == "doScene" }
        candidateCounts[K_TYPING_DO_SCENE] = all.size
        if (all.isEmpty()) Logger.w(TAG, "${clazz.name} 及其父类中没有 doScene")
        else Logger.i(TAG, "TypingScene -> ${clazz.name}.doScene（候选 ${all.size} 个）")
        return all.take(MAX_CANDIDATES)
    }

    private fun findClassByStrings(
        bridge: DexKitBridge,
        cl: ClassLoader,
        pkg: String,
        strings: Array<String>,
    ): Class<*>? = runCatching {
        bridge.findClass {
            searchPackages(pkg)
            matcher { usingEqStrings(*strings) }
        }.firstOrNull()?.getInstance(cl)
    }.onFailure { Logger.e(TAG, "搜索类 $pkg 失败", it) }.getOrNull()

    private fun findMethodByStrings(
        bridge: DexKitBridge,
        cl: ClassLoader,
        spec: Spec,
    ): List<Method> {
        val all = runCatching {
            bridge.findMethod {
                spec.pkg?.let { searchPackages(it) }
                matcher {
                    spec.methodName?.let { name = it }
                    // 空数组展开给 usingEqStrings 在部分 DexKit 版本会变成「无条件」，
                    // 这里显式跳过，只按方法名/包名匹配。
                    if (spec.strings.isNotEmpty()) usingEqStrings(*spec.strings)
                }
            }
        }.onFailure { Logger.e(TAG, "搜索方法 ${spec.key} 失败", it) }.getOrNull() ?: return emptyList()

        candidateCounts[spec.key] = all.size
        if (all.size > 1) {
            // WeKit 在这里会直接抛异常；我们全部 hook —— 其中通常只有一个会被真正调用。
            Logger.w(TAG, "${spec.key} 匹配到 ${all.size} 个候选，全部 hook：" +
                all.joinToString { "${it.className}->${it.methodName}${it.methodSign}" })
        }

        val out = LinkedHashMap<String, Method>()
        for (data in all.take(MAX_CANDIDATES)) {
            DexSig.find(cl, data.className, data.methodName, data.methodSign)
                ?.let { out["${data.className}->${data.methodName}${data.methodSign}"] = it }
        }
        if (out.isEmpty()) Logger.w(TAG, "${spec.key} 有 ${all.size} 个候选但反射还原全部失败")
        else Logger.i(TAG, "${spec.key} 候选 ${all.size} 个，可 hook ${out.size} 个：${out.keys.joinToString()}")
        return out.values.toList()
    }

    fun findMethodInHierarchy(clazz: Class<*>, name: String): Method? =
        findMethodInHierarchyList(clazz) { it.name == name }.firstOrNull()

    /** 沿类层级收集所有满足条件的方法（去重，子类优先） */
    fun findMethodInHierarchyList(clazz: Class<*>, filter: (Method) -> Boolean): List<Method> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Method>()
        var cursor: Class<*>? = clazz
        while (cursor != null) {
            cursor.declaredMethods.forEach { m ->
                if (filter(m) && seen.add("${m.name}${DexSig.methodSig(m)}")) {
                    m.isAccessible = true
                    out += m
                }
            }
            cursor = cursor.superclass
        }
        return out
    }
}
