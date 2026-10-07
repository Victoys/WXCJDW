package dev.mm.wxcj

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在本地缓存「消息 ID → 内容摘要」，供防撤回提示显示**被撤回的那条到底是什么**。
 *
 * ## 为什么需要它
 *
 * 微信的撤回 sysmsg 里**只有撤回者名字，没有消息内容**——
 * `replacemsg` 就是那句「xx 撤回了一条消息」，内容早在改写原消息行时就被销毁了。
 *
 * 所以内容必须在**消息到达时就留一份快照**，等撤回发生再按 msgSvrId 反查。
 *
 * ## 切入点
 *
 * `com.tencent.mm.storage.MsgInfo` 是微信历史最久、最稳定的存储类之一，
 * 类名和方法名 `setContent(String)` 在混淆中基本都保留。
 * 所有消息（收到 / 发出）都会经过它，因此是天然的快照点。
 *
 * ## 安全性
 *
 *  **只读**。hook 发生在 `afterHookedMethod`，只读取 `this` 的 msgSvrId 和参数内容，
 *  不修改任何东西，不碰数据库。定位失败时整个功能静默降级（提示里只显示名字），
 *  **不影响撤回拦截本身**。
 */
object MsgSnapshots {

    private const val TAG = "MsgSnapshots"

    /**
     * 消息存储类的候选名单。
     *
     * 只写死一个 `com.tencent.mm.storage.MsgInfo` 是不够的：微信历史上把它放在
     * `com.tencent.mm.storage.bi`、生成基类在 `com.tencent.mm.g.c.*`，
     * 不同版本位置不同。逐个试，第一个能找到 setContent 的就用它。
     */
    private val MSG_INFO_CANDIDATES = listOf(
        "com.tencent.mm.storage.MsgInfo",
        "com.tencent.mm.storage.bi",
        "com.tencent.mm.storagebase.MsgInfo",
        "com.tencent.mm.g.c.eo",
        "com.tencent.mm.g.c.dy",
        "com.tencent.mm.g.c.ei",
    )

    /** 缓存上限：只关心最近的消息，多了没意义还占内存 */
    private const val MAX_ENTRIES = 300

    /** 展示时内容截断长度 */
    private const val PREVIEW_MAX = 80

    /**
     * 兜底内容的新鲜度窗口。
     *
     * 撤回通常紧跟在被撤消息之后（几秒内），所以「最近一条消息」作为兜底很可靠；
     * 但撤回很久以前的旧消息时它可能已经不准了，超过这个窗口就放弃兜底。
     */
    private const val FALLBACK_WINDOW_MS = 120_000L

    /** 只尝试一次：失败也要留下原因，不重复扫。 */
    private val installTrying = AtomicBoolean(false)

    /** 最终选定的消息存储类名，诊断用。 */
    @Volatile
    private var resolvedClass: String = ""

    /** msgSvrId -> 内容摘要。同步包装，读取发生在 hook 回调线程。 */
    private val cache: MutableMap<Long, String> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean =
                size > MAX_ENTRIES
        },
    )

    /**
     * msgSvrId -> 是否**本设备发出**。
     *
     * 用途：区分「对方撤回」和「自己撤回」。自己主动撤回时不该弹拦截提示
     * （本来就是用户自己想撤掉的），也不该把消息强行留下。
     *
     * 读不到就**不写入**，[isSelf] 相应返回 null，调用方按「非自己」处理。
     */
    private val sendFlags: MutableMap<Long, Boolean> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?): Boolean =
                size > MAX_ENTRIES
        },
    )

    /**
     * MsgInfo 实例 -> 内容。
     *
     * 为什么需要：自己**发出去**的消息，`msgSvrId` 是服务器返回后才赋值的，
     * 在 `setContent` 那一刻通常还是 0，直接 `id <= 0 return` 就永远记不下来 ——
     * 这正是「自己撤回判定失效」的根因。所以先按实例存一份，等 id 确定了再落库。
     */
    private val pendingContent: MutableMap<Any, String> =
        Collections.synchronizedMap(IdentityHashMap<Any, String>())

    /** 最近一条消息的内容 + 时间戳（兜底用，不依赖 msgSvrId）。 */
    @Volatile
    private var lastContent: String? = null

    @Volatile
    private var lastContentMs = 0L

    /** talker -> (内容, 时间戳)。比全局兜底更准。 */
    private val lastByTalker: MutableMap<String, Pair<String, Long>> =
        Collections.synchronizedMap(LinkedHashMap())

    @Volatile
    private var available = false

    /** 装载失败的原因，诊断里直接显示，省得翻日志。 */
    @Volatile
    private var failReason: String = ""

    fun isAvailable() = available

    /**
     * 装载快照 hook。
     *
     * @return 是否成功；失败时 [lookup] 恒返回 null，功能自动降级。
     */
    /** 兼容旧调用（拿不到 Context 时）。 */
    fun install(classLoader: ClassLoader): Boolean = install(null, classLoader)

    /**
     * 装载快照 hook。
     *
     * 优先用写死的候选类名（快）；全部未命中时改用 [HostClassScanner]
     * **去宿主 dex 里枚举类名**再按特征打分 —— 微信换了类名也能适应。
     *
     * @return 是否成功；失败时 [lookup] 恒返回 null，功能自动降级。
     */
    fun install(context: Context?, classLoader: ClassLoader): Boolean {
        if (!installTrying.compareAndSet(false, true)) return available

        // 逐个试候选类：找到「类存在 且 有 content 字段」的那个
        var clazz: Class<*>? = null
        val tried = ArrayList<String>()

        for (name in MSG_INFO_CANDIDATES) {
            val c = runCatching { Class.forName(name, false, classLoader) }
                .onFailure { tried += "$name(无此类)" }
                .getOrNull() ?: continue
            if (HostClassScanner.findStringField(c, "content") == null) {
                tried += "$name(无 content 字段)"
                continue
            }
            clazz = c
            Logger.i(TAG, "选用消息存储类：$name")
            break
        }

        if (clazz == null) {
            // 8.0.78 上写死候选**必然**全部落空（类名被混淆），走 dex 枚举是预期路径，
            // 不是异常 —— 用 INFO 而不是 WARN，免得看日志的人以为出了问题。
            Logger.i(TAG, "写死候选全部未命中：${tried.joinToString("、")}，改用 dex 枚举（预期路径）")
            val scanned = runCatching { HostClassScanner.findMessageInfoClass(context, classLoader) }
                .getOrElse { HostClassScanner.Result(null, "扫描异常：${it.message}") }
            val c = scanned.clazz
            if (c == null) {
                failReason = "写死候选与 dex 枚举均未命中（${scanned.reason}）"
                Logger.w(TAG, failReason)
                return false
            }
            clazz = c
            Logger.i(TAG, "dex 枚举命中：${c.name}")
        }
        resolvedClass = clazz.name

        // 以下四项全是**可选增强**。拿不到任何一项也不该让整个快照功能失效 ——
        // 只要能挂上 setter，就至少能用「最近一条消息」兜底显示内容。
        val idGetter = findMsgSvrIdGetter(clazz)
        val isSendAccessor = findIsSendAccessor(clazz)
        val talkerAccessor = findTalkerAccessor(clazz)
        val idSetter = findMsgSvrIdSetter(clazz)
        val contentField = HostClassScanner.findStringField(clazz, "content")

        Logger.i(TAG, "msgSvrId 读取：${accessorName(idGetter)}")
        Logger.i(TAG, "msgSvrId 写入：${accessorName(idSetter)}")
        Logger.i(TAG, "isSend 读取：${accessorName(isSendAccessor)}")
        Logger.i(TAG, "talker 读取：${accessorName(talkerAccessor)}")
        Logger.i(TAG, "content 字段：${contentField?.name ?: "未找到（将用 setter 入参）"}")
        if (idSetter != null) hookIdSetter(idSetter, isSendAccessor, contentField, talkerAccessor)

        // 方法名被混淆，只能把单 String 参数的 setter 全挂上，
        // 回调里再用 content 字段的值校验「这次是不是真的在设内容」。
        val setters = HostClassScanner.findStringSetters(clazz)
        if (setters.isEmpty()) {
            failReason = "在 ${clazz.name} 上找不到单 String 参数的 setter"
            Logger.w(TAG, failReason)
            return false
        }

        var hooked = 0
        for (m in setters) {
            val ok = runCatching { hookStringSetter(m, contentField, idGetter, isSendAccessor, talkerAccessor) }
                .onFailure { Logger.w(TAG, "挂 ${m.name} 失败：${it.message}") }
                .getOrDefault(false)
            if (ok) hooked++
        }

        if (hooked == 0) {
            failReason = "setter 全部挂载失败（${setters.size} 个）"
            Logger.w(TAG, failReason)
            return false
        }

        available = true
        Logger.i(TAG, "快照 hook 已装载：${clazz.name}，setter $hooked/${setters.size} 个")
        return true
    }

    /**
     * 挂一个单 String 参数的 setter。
     *
     * **校验逻辑是关键**：如果 content 字段能读到值，只有「字段值 == 入参」时才认为
     * 这次确实是在设内容 —— 否则像 setTalker / setImgPath 这类 setter 也会被误当成
     * 内容写入，把「最近一条」污染成别的内容。
     */
    private fun hookStringSetter(
        m: Method,
        contentField: Field?,
        idGetter: Any?,
        isSendAccessor: Any?,
        talkerAccessor: Any?,
    ): Boolean {
        m.isAccessible = true
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val arg = param.args[0] as? String ?: return
                    val target = param.thisObject ?: return

                    val isContent = contentField?.let {
                        it.isAccessible = true
                        (it.get(target) as? String)?.takeIf { v -> v == arg }
                    } != null

                    // 也要识别「这次设的是 talker」：3.5 日志显示「按会话 0 个」，
                    // 说明设 content 时 talker 还是空的，按会话兜底从未建立。
                    val talkerValue = talkerAccessor?.let { readTalker(it, target) }
                    val isTalker = talkerValue != null && talkerValue == arg

                    if (!isContent && !isTalker) return
                    if (arg.isBlank()) return

                    if (isTalker) {
                        // 设 talker 时：把该实例已攒的内容（或全局最近一条）关联到这个会话
                        val summary = pendingContent[target] ?: lastContent ?: return
                        lastByTalker[arg] = summary to System.currentTimeMillis()
                        return
                    }

                    val summary = humanize(arg)
                    pendingContent[target] = summary

                    // 兜底：无论拿不拿得到 msgSvrId，都记下「最近一条」。
                    lastContent = summary
                    lastContentMs = System.currentTimeMillis()
                    val t = talkerValue ?: readTalker(talkerAccessor, target)
                    if (t != null) lastByTalker[t] = summary to lastContentMs

                    val id = readId(idGetter, target)
                    if (id == null || id <= 0L) return   // id 还是 0：等 setter 回调补建
                    store(id, summary, isSendAccessor, target)
                }
            }
        })
        return true
    }

    /** 按 msgSvrId 取内容摘要；没有就返回 null。 */
    fun lookup(msgSvrId: Long): String? {
        if (msgSvrId <= 0L) return null
        return cache[msgSvrId]
    }

    /**
     * 该消息是不是**本设备发出**的。
     *
     * @return true=自己发的，false=对方发的，**null=没记录**（快照未命中或读不到 isSend）。
     *         调用方对 null 要谨慎处理，不要当成「对方发的」而误拦。
     */
    fun isSelf(msgSvrId: Long): Boolean? {
        if (msgSvrId <= 0L) return null
        return sendFlags[msgSvrId]
    }

    /**
     * 找 setContent(String)。
     *
     * **必须沿继承链找**——`declaredMethods` 不含继承来的方法，而微信的 MsgInfo
     * 是代码生成的，`setContent` 常在生成器基类（如 `com.tencent.mm.g.c.*`）里。
     * 只查当前类会直接定位失败，这就是「内容快照：可用=false」的根因。
     */


    /**
     * 找 msgSvrId 的读取方式：先找 getter 方法，再找字段。
     * 两者都按名字匹配（微信混淆时这类核心字段名通常保留）。
     */
    private fun findMsgSvrIdGetter(clazz: Class<*>): Any? {
        var mc: Class<*>? = clazz
        while (mc != null) {
            mc.declaredMethods.firstOrNull {
                it.parameterCount == 0 &&
                    (it.returnType == java.lang.Long.TYPE || it.returnType == java.lang.Long::class.java) &&
                    it.name.contains("svrId", ignoreCase = true)
            }?.let { return it }
            mc = mc.superclass
        }

        // 字段：优先 field_msgSvrId，其次任何含 svrId 的 long 字段
        var cursor: Class<*>? = clazz
        while (cursor != null) {
            cursor.declaredFields.firstOrNull {
                it.type == java.lang.Long.TYPE && it.name.contains("msgsvrid", ignoreCase = true)
            }?.let { return it }
            cursor = cursor.superclass
        }
        cursor = clazz
        while (cursor != null) {
            cursor.declaredFields.firstOrNull {
                it.type == java.lang.Long.TYPE &&
                    (it.name.contains("svrId", ignoreCase = true) || it.name.contains("svrid", ignoreCase = true))
            }?.let { return it }
            cursor = cursor.superclass
        }
        return null
    }

    /** 统一落库：内容 + isSend。 */
    private fun store(id: Long, summary: String, isSendAccessor: Any?, target: Any?) {
        cache[id] = summary
        val self = readIsSend(isSendAccessor, target)
        if (self != null) sendFlags[id] = self
    }

    /** 挂 msgSvrId 的 setter：id 确定后再把之前攒的内容落库。 */
    private fun hookIdSetter(
        setter: Method,
        isSendAccessor: Any?,
        contentField: Field?,
        talkerAccessor: Any?,
    ) {
        runCatching {
            setter.isAccessible = true
            XposedBridge.hookMethod(setter, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val id = (param.args[0] as? Number)?.toLong() ?: return
                        if (id <= 0L) return
                        val target = param.thisObject ?: return
                        // pending 里没有就直接读 content 字段 —— 覆盖「先设 id 再设内容」的顺序
                        val summary = pendingContent[target]
                            ?: run {
                                contentField?.let {
                                    it.isAccessible = true
                                    (it.get(target) as? String)
                                }?.takeIf { it.isNotBlank() }?.let { humanize(it) }
                            }
                            ?: return
                        store(id, summary, isSendAccessor, target)
                        lastContent = summary
                        lastContentMs = System.currentTimeMillis()
                        readTalker(talkerAccessor, target)?.let {
                            lastByTalker[it] = summary to lastContentMs
                        }
                        pendingContent.remove(target)
                    }
                }
            })
            Logger.i(TAG, "msgSvrId setter hook 已装载")
        }.onFailure { Logger.w(TAG, "装载 msgSvrId setter hook 失败：${it.message}") }
    }

    /**
     * 找 msgSvrId 的**写入**方法：参数 1 个、long 型、名字含 svrId / svrid。
     *
     * ## 不要放宽到 msgId
     *
     * 微信里 `msgId`（本地自增 ID）和 `msgSvrId`（服务器 ID）是**两个不同的东西**。
     * 之前匹配条件里带了 `msgid`/`msgId`，8.0.78 实测挂到了 `setMsgId()` ——
     * 把本地 ID 当服务器 ID 存进缓存，而撤回 sysmsg 里的 `newmsgid` 是服务器 ID，
     * 两者永远对不上，表现为「可用=true 但缓存 0 条」，精确索引一条都没建起来。
     *
     * 所以这里只认 svrId。找不到就不挂（宁缺勿错），内容仍有「最近一条」兜底。
     */
    private fun findMsgSvrIdSetter(clazz: Class<*>): Method? {
        var cursor: Class<*>? = clazz
        while (cursor != null) {
            cursor.declaredMethods.firstOrNull {
                it.parameterCount == 1 &&
                    (it.parameterTypes[0] == java.lang.Long.TYPE ||
                        it.parameterTypes[0] == java.lang.Long::class.java) &&
                    (it.name.contains("svrId", ignoreCase = true) ||
                        it.name.contains("svrid", ignoreCase = true))
            }?.let { return it }
            cursor = cursor.superclass
        }
        return null
    }

    /**
     * 找「是否自己发出」的读取方式。
     *
     * `com.tencent.mm.storage.MsgInfo` 的 `field_isSend` 是数据库列 `isSend` 的直接映射，
     * 在混淆里通常保留（带 `field_` 前缀或 `isSend` 方法名）。
     */
    private fun findIsSendAccessor(clazz: Class<*>): Any? {
        var mc: Class<*>? = clazz
        while (mc != null) {
            mc.declaredMethods.firstOrNull {
                it.parameterCount == 0 &&
                    (it.returnType == java.lang.Boolean.TYPE ||
                        it.returnType == java.lang.Boolean::class.java ||
                        it.returnType == java.lang.Integer.TYPE) &&
                    it.name.contains("isSend", ignoreCase = true)
            }?.let { return it }
            mc = mc.superclass
        }

        var cursor: Class<*>? = clazz
        while (cursor != null) {
            cursor.declaredFields.firstOrNull {
                it.name.contains("isSend", ignoreCase = true) &&
                    (it.type == java.lang.Integer.TYPE ||
                        it.type == java.lang.Boolean.TYPE ||
                        it.type == java.lang.Integer::class.java ||
                        it.type == java.lang.Boolean::class.java)
            }?.let { return it }
            cursor = cursor.superclass
        }
        return null
    }

    private fun readIsSend(accessor: Any?, target: Any?): Boolean? {
        if (accessor == null || target == null) return null
        val raw = runCatching {
            when (accessor) {
                is Method -> {
                    accessor.isAccessible = true
                    accessor.invoke(target)
                }
                is Field -> {
                    accessor.isAccessible = true
                    accessor.get(target)
                }
                else -> null
            }
        }.onFailure { Logger.w(TAG, "读取 isSend 失败：${it.message}") }.getOrNull()

        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            else -> null
        }
    }

    /** 找 talker 的读取方式：`field_talker` 在微信里长期存在。 */
    private fun findTalkerAccessor(clazz: Class<*>): Any? {
        var mc: Class<*>? = clazz
        while (mc != null) {
            mc.declaredMethods.firstOrNull {
                it.parameterCount == 0 &&
                    it.returnType == String::class.java &&
                    it.name.contains("talker", ignoreCase = true)
            }?.let { return it }
            mc = mc.superclass
        }

        var cursor: Class<*>? = clazz
        while (cursor != null) {
            cursor.declaredFields.firstOrNull {
                it.type == String::class.java && it.name.contains("talker", ignoreCase = true)
            }?.let { return it }
            cursor = cursor.superclass
        }
        return null
    }

    private fun readTalker(accessor: Any?, target: Any?): String? {
        if (accessor == null || target == null) return null
        val raw = runCatching {
            when (accessor) {
                is Method -> { accessor.isAccessible = true; accessor.invoke(target) }
                is Field -> { accessor.isAccessible = true; accessor.get(target) }
                else -> null
            }
        }.getOrNull()
        val s = raw as? String ?: return null
        return if (s.isBlank()) null else s
    }

    /** 查询结果：text=内容，exact=true 表示按 msgSvrId 精确命中。 */
    data class Found(val text: String, val exact: Boolean)

    /**
     * 综合查询：id 精确 → 同会话最近一条 → 全局最近一条。
     *
     * 后两者是兜底，[Found.exact] 为 false，调用方应标注「可能」以免误导。
     */
    fun contentFor(msgSvrId: Long?, talker: String?): Found? {
        if (msgSvrId != null && msgSvrId > 0L) {
            cache[msgSvrId]?.let { return Found(it, true) }
        }
        val now = System.currentTimeMillis()
        if (!talker.isNullOrBlank()) {
            lastByTalker[talker]?.let { (text, ts) ->
                if (now - ts <= FALLBACK_WINDOW_MS) return Found(text, false)
            }
        }
        val text = lastContent ?: return null
        val ts = lastContentMs
        return if (ts > 0 && now - ts <= FALLBACK_WINDOW_MS) Found(text, false) else null
    }

    /** 诊断用：一眼看出快照功能断在哪一步。 */
    fun diagnostics(): String = buildString {
        append("可用=$available")
        if (!available && failReason.isNotBlank()) append("，原因：$failReason")
        append("，缓存 ${cache.size} 条")
        append("，最近=")
        append(lastContent?.let { truncate(it) } ?: "无")
        val age = if (lastContentMs > 0) (System.currentTimeMillis() - lastContentMs) / 1000 else -1
        append("（${age}秒前）")
        append("，按会话 ${lastByTalker.size} 个")
        if (resolvedClass.isNotBlank()) append("，类=${resolvedClass.substringAfterLast('.')}")
    }

    private fun accessorName(accessor: Any?): String = when (accessor) {
        null -> "未找到（自己撤回判定不可用）"
        is Method -> "方法 ${accessor.name}()"
        is Field -> "字段 ${accessor.name}"
        else -> "未知"
    }

    private fun readId(accessor: Any?, target: Any?): Long? {
        if (accessor == null || target == null) return null
        return when (accessor) {
            is Method -> {
                accessor.isAccessible = true
                (accessor.invoke(target) as? Number)?.toLong()
            }
            is Field -> {
                accessor.isAccessible = true
                (accessor.get(target) as? Number)?.toLong()
            }
            else -> null
        }
    }

    /**
     * 把原始 content 变成人能看的一句话。
     *
     * 文本消息的 content 就是纯文本；图片/语音/视频/分享卡片等是 XML，
     * 直接显示会是一大坨标签，所以做归类。
     */
    private fun humanize(content: String): String {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return "[空消息]"
        if (!trimmed.startsWith("<")) return trimmed

        val lower = trimmed.lowercase()
        return when {
            lower.contains("<emoji") || lower.contains("<gameext") -> "[表情]"
            lower.contains("<img") -> "[图片]"
            lower.contains("<video") || lower.contains("<videomsg") -> "[视频]"
            lower.contains("<voicemsg") || lower.contains("<voice") -> "[语音]"
            lower.contains("<appmsg") -> extractAppMsgTitle(trimmed) ?: "[分享/卡片消息]"
            lower.contains("<location") -> "[位置]"
            lower.contains("<cardmsg") || lower.contains("<msg") && lower.contains("<card") -> "[名片]"
            lower.contains("<sysmsg") -> "[系统消息]"
            lower.contains("<file") || lower.contains("<appattach") -> "[文件]"
            lower.contains("<record") -> "[聊天记录]"
            else -> "[多媒体消息]"
        }
    }

    /** 分享卡片里通常有 <title>，取出来比「[分享消息]」有用得多。 */
    private fun extractAppMsgTitle(xml: String): String? {
        val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.getOrNull(1)
            ?.replace(Regex("<!\\[CDATA\\[|]]>"), "")
            ?.trim()
        if (title.isNullOrEmpty()) return null
        return "[分享] ${truncate(title)}"
    }

    /** 展示用截断：太长的消息在弹窗里没意义，还会把按钮挤下去。 */
    fun truncate(text: String): String {
        val t = text.replace('\n', ' ').replace('\r', ' ').trim()
        return if (t.length <= PREVIEW_MAX) t else t.substring(0, PREVIEW_MAX) + "…"
    }
}
