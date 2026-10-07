package dev.mm.wxcj

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * 消息防撤回。
 *
 * ## 微信撤回到底做了什么（决定性事实）
 *
 * WeKit 源码里有一句注释直接点破：
 *
 * > WeChat only marks a message as revoked **AFTER overwriting its row in place**
 * > (destroying the content).
 *
 * 也就是说：**「xx 撤回了一条消息」并不是一条新插入的提示消息，
 * 它就是被就地改写的那条原消息行本身。**
 *
 * 由此推出一个重要结论，也是 v2.1 那个「提示模式」失败的根因：
 * **保住消息**和**显示提示**在微信的设计里是互斥的——
 * 撤回动作成功 = 原消息行被改写 = 内容消失、提示出现；
 * 撤回动作被拦 = 消息原样留下 = 什么提示都不会有。
 *
 * 所以本模块**只做保住消息**，提示改用模块自己的浮层来实现（见 [showNotice]）。
 *
 * ## 两条独立拦截路径（互为备份）
 *
 * 1. **sysmsg 路径**（[install]）：微信把撤回当作 sysmsg 下发，XmlParser 解析成 Map，
 *    把 `.sysmsg.$type` 置 null → 微信不认识这条 sysmsg，撤回分支完全不执行。
 * 2. **doRevokeMsg 路径**（[installDoRevoke]）：直接取消微信的撤回处理方法。
 *    特征串来自 WAuxiliary 源码（经确认的真实指纹）：
 *    `doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s`
 *
 * 两条都挂上，任一生效消息就保得住。诊断模式会分别报各自拦截了多少条。
 *
 * ## 已知边界
 *  - 本设备自己发出的消息撤回走 `NetSceneRevokeMsg`，不经 sysmsg，本模块不处理 ——
 *    测试时请让**对方**撤回。
 */
object AntiRecall {

    private const val TAG = "AntiRecall"

    private const val TYPE_KEY = ".sysmsg.\$type"
    private const val TYPE_REVOKE = "revokemsg"

    /** 撤回 sysmsg 里指向被撤回消息的 id（字符串形式的数字） */
    private const val NEW_MSG_ID_KEY = ".sysmsg.revokemsg.newmsgid"
    private const val MSG_ID_KEY = ".sysmsg.revokemsg.msgid"

    private val hitCount = AtomicInteger(0)        // sysmsg 路径拦截数
    private val callCount = AtomicInteger(0)       // XmlParser 总调用数
    private val revokeHitCount = AtomicInteger(0)  // doRevokeMsg 路径拦截数
    private val revokeCallCount = AtomicInteger(0) // doRevokeMsg 总调用数

    @Volatile
    var verbose = false

    // ---- 提示配置（由 HookEntry 在装载前注入）----

    /**
     * true = 拦截后弹一条**必须手动点「知道了」才会消失**的浮层，
     *        内容形如「已拦截「张三」撤回：今晚吃饭吗？」；
     * false = 只弹 3 秒自动消失的通用提示。
     *
     * 注意：这里的「提示」是**模块自己的浮层**，不是聊天里的系统消息。
     * 聊天里插文字需要往微信加密数据库写行，风险过高，不做。
     */
    @Volatile
    var showNotice = false

    /**
     * 提示模板。可用占位符：
     *  - `$sender`：撤回者名字
     *  - `$content`：被撤回消息的内容摘要（取不到时为「(未知内容)」）
     */
    @Volatile
    var noticeTemplate = "已拦截「\$sender」撤回：\n\$content"

    /**
     * true = 拦截提示**常驻**，必须手动点「知道了」才消失；false = 3 秒自动消失。
     *
     * 与 [showNotice] 互相独立：[showNotice] 决定「显示多少信息」，
     * 这一项决定「要不要等用户确认」。默认 true —— 撤回提示错过就再也看不到了。
     */
    @Volatile
    var confirmNotice = true

    /**
     * true = **也拦截并提示自己撤回的消息**；false = 只管对方撤回的。
     *
     * 默认 false，理由：自己撤回是用户主动想让消息消失，把它留下来反而添乱，
     * 而且自己发的消息内容你自己清楚，提示没有价值。
     * 这与 WeKit 的 `recallOutgoing`（默认 false）一致。
     */
    @Volatile
    var recallSelf = false

    val triggeredCount get() = callCount.get()
    val blockedCount get() = hitCount.get()
    val revokeTriggeredCount get() = revokeCallCount.get()
    val revokeBlockedCount get() = revokeHitCount.get()

    // ── 路径一：改写 sysmsg ────────────────────────────────────────────────

    fun install(methods: List<Method>): Int {
        var n = 0
        methods.forEach { method ->
            runCatching { hookOne(method) }
                .onSuccess { n++ }
                .onFailure { Logger.e(TAG, "hook 失败：${method.declaringClass.name}.${method.name}", it) }
        }
        if (n == 0) Logger.w(TAG, "sysmsg 路径：没有任何候选挂载成功")
        return n
    }

    private fun hookOne(method: Method) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                @Suppress("UNCHECKED_CAST")
                val map = param.result as? MutableMap<String, Any?> ?: return

                val n = callCount.incrementAndGet()
                val type = map[TYPE_KEY]

                if (n <= 3) {
                    Logger.i(TAG, "XmlParser 第 $n 次调用，type=$type，共 ${map.size} 个键")
                }

                if (type == TYPE_REVOKE && verbose) {
                    // 把整张表打出来：一眼确认 replacemsg 到底叫什么、值是什么
                    Logger.i(TAG, "撤销 sysmsg 键清单：${map.keys.joinToString()}")
                    map.entries.take(12).forEach { (k, v) -> Logger.i(TAG, "  $k = $v") }
                }

                if (type != TYPE_REVOKE) {
                    if (verbose) {
                        // 只提示**一次**：这条本来是给排查用的「hook 已通」确认，
                        // 但 XmlParser 会被各种 sysmsg 频繁调用（切设置、刷朋友圈都会），
                        // 每 20 秒弹一次就成了噪音，看起来像报错。
                        if (callNoticeShown.compareAndSet(false, true)) {
                            Notifier.notify("防撤回：hook 已通（XmlParser 已被调用），等待撤回指令；此后不再重复提示")
                        }
                        Logger.i(TAG, "XmlParser 第 ${n} 次调用，type=$type，共 ${map.size} 个键")
                    }
                    return
                }

                // 自己撤回的消息：默认放行（让微信正常撤掉），也不弹提示
                if (isSelfSysMsg(map) && !recallSelf) {
                    Logger.i(TAG, "自己撤回的消息，按配置放行（不拦截、不提示）")
                    return
                }

                val hits = hitCount.incrementAndGet()

                // 置空即可让微信的撤回分支失效。用 null 而非移除 key，
                // 避免下游对缺失 key 的 NPE 路径。
                map[TYPE_KEY] = null
                Logger.i(TAG, "sysmsg 路径已拦截（第 $hits 条）")

                notifyBlocked(
                    sender = senderOfSysMsg(map),
                    content = contentOfSysMsg(map),
                    talker = talkerOfSysMsg(map),
                    hits = hits,
                )
            }
        })
        Logger.i(TAG, "sysmsg hook 已安装：${method.declaringClass?.name}.${method.name}")
    }

    // ── 路径二：直接取消 doRevokeMsg ───────────────────────────────────────

    /**
     * WAuxiliary 的做法：定位到微信的撤回处理方法后，在 **before** 里直接
     * `param.result = null` 取消整个方法。
     *
     * 与 sysmsg 路径完全独立，因此一条失效时另一条仍可能生效。
     */
    fun installDoRevoke(methods: List<Method>): Int {
        var n = 0
        methods.forEach { method ->
            runCatching { hookDoRevokeOne(method) }
                .onSuccess { n++ }
                .onFailure { Logger.e(TAG, "doRevokeMsg hook 失败：${method.declaringClass.name}.${method.name}", it) }
        }
        if (n == 0) Logger.w(TAG, "doRevokeMsg 路径：没有候选挂载成功（该版本可能没有这个日志串）")
        return n
    }

    private fun hookDoRevokeOne(method: Method) {
        method.isAccessible = true
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val n = revokeCallCount.incrementAndGet()

                // 自己撤回的消息：放行，**连 result 都不置空**，让微信正常走完撤回流程
                if (shouldSkipDoRevoke(param.args)) {
                    Logger.i(TAG, "doRevokeMsg 放行（总调用 $n）：$lastSkipReason")
                    return
                }

                // 原方法被跳过 → 本地撤回逻辑（改写/删除消息行）不会发生
                param.result = null
                val hits = revokeHitCount.incrementAndGet()
                Logger.i(TAG, "doRevokeMsg 路径已拦截（第 $hits 条，总调用 $n）")
                notifyBlocked(
                    sender = senderFromArgs(param.args),
                    content = contentFromArgs(param.args),
                    talker = talkerFromArgs(param.args),
                    hits = hits,
                )
            }
        })
        val owner = method.declaringClass.name
        Logger.i(TAG, "doRevokeMsg hook 已安装：$owner.${method.name}")
        // 8.0.78 实测挂到过 b41.t.c（不像微信的类，且调用 0 次）。
        // 明确告警，避免把「挂错地方」误当成「hook 未生效」。
        if (!owner.startsWith("com.tencent.mm")) {
            Logger.w(TAG, "doRevokeMsg 挂到了非微信类 $owner，多半是特征串匹配错了，该路径大概率空转")
        }
    }

    // ── 提示 ──────────────────────────────────────────────────────────────

    /**
     * 两条路径共用的提示逻辑。
     *
     * 开了 [showNotice] 时用 [Notifier.confirm]——**不会自动消失**，
     * 锁屏时产生的也会排队，回到微信再逐条弹，避免错过。
     */
    private fun notifyBlocked(sender: String?, content: String?, talker: String?, hits: Int) {
        val text = if (showNotice) {
            val who = if (sender.isNullOrBlank()) "对方" else sender
            val what = describeContent(content, talker)
            noticeTemplate
                .replace("\$sender", who)
                .replace("\$content", what)
        } else {
            "防撤回：已拦截 1 条撤回指令（累计 $hits 条）"
        }

        if (confirmNotice) {
            Notifier.confirm(text)
            Logger.i(TAG, "待确认提示：$text")
        } else {
            Notifier.notify(text)
            Logger.i(TAG, "提示：$text")
        }
    }

    // ── 自己撤回 vs 对方撤回 ──────────────────────────────────────────────

    /**
     * 判定这条撤回是不是**本设备自己发的消息被撤回**。
     *
     * 两个信号，任一成立即可判定：
     *  1. `replacemsg` 以「你撤」开头 —— 微信对自己撤回用的是「你撤回了一条消息」，
     *     对别人则是「「张三」撤回了一条消息」，这个区分长期稳定。
     *  2. 快照里查到该 msgSvrId 的 `isSend` 为真。
     *
     * 两个都拿不到时返回 false（当作对方撤回），保证拦截不会漏。
     */
    private fun isSelfSysMsg(map: Map<String, Any?>): Boolean {
        val replaceKey = map.keys.firstOrNull { it.endsWith("replacemsg") }
        val original = replaceKey?.let { map[it] as? String }?.trim()
        if (!original.isNullOrBlank() && (original.startsWith("你撤") || original == "你")) return true

        val id = firstId(map, NEW_MSG_ID_KEY) ?: firstId(map, MSG_ID_KEY) ?: return false
        return MsgSnapshots.isSelf(id) == true
    }

    /** 最近一次放行原因，纯诊断用（会写进日志）。 */
    @Volatile
    private var lastSkipReason: String = ""

    /**
     * doRevokeMsg 路径：这一条要不要放行（不拦截、不提示）。
     *
     * ## 为什么这里必须用「不能证明是对方的就放行」
     *
     * 两条路径的分工是不对称的，这决定了判定策略必须相反：
     *
     *  - **sysmsg 路径只走对方的撤回**。WeKit 源码明确写了：
     *    「recalling a message sent from this device **never parses a revoke sysmsg**」。
     *    自己撤回走 `NetSceneRevokeMsg`，直接改写本地消息行，压根不经过 XmlParser。
     *    所以到达这条路径的一定是别人撤的 —— 可以放开拦截，查不到也照样拦。
     *  - **doRevokeMsg 路径两条都走**，而且它拿不到 `replacemsg`
     *    （那句「你撤回了一条消息」才是判定自己撤回最可靠的信号），
     *    只能靠快照里的 `isSend` 反查。
     *
     * 而 `isSend` 恰恰**经常查不到**：自己发出去的消息，`msgSvrId` 是服务器返回后才
     * 赋值的，在 `MsgInfo.setContent` 那一刻往往还是 0，快照直接跳过
     * （[MsgSnapshots] 里 `id <= 0` 就 return），于是 `isSelf()` 返回 null。
     *
     * 之前把 null 当「不是自己」处理 → 拦截 + 弹提示，正是你看到的现象。
     * 现在反过来：**只有明确查到 isSend=false（确实是对方发的）才拦截**，
     * 查不到就放行。宁可放过一条，也不该把自己主动撤回的消息硬留下来。
     */
    private fun shouldSkipDoRevoke(args: Array<Any?>?): Boolean {
        // 开关打开时：连自己撤回的也拦截，不再判定
        if (recallSelf) {
            lastSkipReason = ""
            return false
        }

        return when (selfFlagFromArgs(args)) {
            true -> {
                lastSkipReason = "确认是自己发的（isSend=true）"
                true
            }
            false -> {
                lastSkipReason = ""
                false
            }
            null -> {
                lastSkipReason = "无法判定（快照里查不到 isSend）→ 按自己撤回放行"
                true
            }
        }
    }

    /**
     * 从 doRevokeMsg 参数里的 long 型 msgSvrId 反查 isSend。
     * @return true=自己发的，false=对方发的，null=查不到
     */
    private fun selfFlagFromArgs(args: Array<Any?>?): Boolean? {
        if (args == null) return null
        var unknown = false
        var sawId = false
        for (a in args) {
            val id = when (a) {
                is Long -> a
                is java.lang.Long -> a.toLong()
                else -> continue
            }
            if (id <= 0L) continue
            sawId = true
            when (MsgSnapshots.isSelf(id)) {
                true -> return true
                false -> Unit          // 继续看，参数里可能有多个 id
                null -> unknown = true
            }
        }
        if (!sawId) return null
        return if (unknown) null else false
    }

    /**
     * 决定提示里「被撤回内容」显示什么。
     *
     * 三级：调用方直接给的内容 → 快照按 (id, talker) 查 → 「未知内容」。
     * 兜底命中（同会话 / 全局最近一条）时加「可能」前缀，避免把不确定的内容当事实。
     */
    private fun describeContent(content: String?, talker: String?): String {
        if (!content.isNullOrBlank()) return MsgSnapshots.truncate(content)

        val found = MsgSnapshots.contentFor(lastSysMsgId, talker)
        if (found == null) return "(未知内容)"
        val body = MsgSnapshots.truncate(found.text)
        return if (found.exact) body else "〔可能〕$body"
    }

    /** 「hook 已通」确认只弹一次，避免刷屏。 */
    private val callNoticeShown = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 最近一次撤回 sysmsg 里的 msgid，供 [describeContent] 二次查询用。 */
    @Volatile
    private var lastSysMsgId: Long? = null

    /** sysmsg 里的会话标识（key 后缀 session / talker / fromusername，前缀各版本不同）。 */
    private fun talkerOfSysMsg(map: Map<String, Any?>): String? {
        val key = map.keys.firstOrNull {
            it.endsWith("session") || it.endsWith("talker") || it.endsWith("fromusername")
        } ?: return null
        val v = map[key] as? String
        return if (v.isNullOrBlank()) null else v
    }

    /** doRevokeMsg 参数里像 talker 的那个（以 @chatroom 结尾或 wxid_ 开头）。 */
    private fun talkerFromArgs(args: Array<Any?>?): String? {
        if (args == null) return null
        for (a in args) {
            val s = a as? String ?: continue
            if (s.isBlank() || s.length > 64) continue
            if (s.trimStart().startsWith("<")) continue
            if (s.endsWith("@chatroom") || s.startsWith("wxid_")) return s
        }
        return null
    }

    /**
     * 从 sysmsg 里取撤回者名字。
     *
     * 只**读取**，不做任何写回——改写 replacemsg 在 type 已置空的前提下毫无意义，
     * 而改写 msgid 反而可能让微信真的撤回消息，属于负优化，已彻底移除。
     */
    /** 用 sysmsg 里的 msgid 去快照表里反查被撤回消息的内容。 */
    private fun contentOfSysMsg(map: Map<String, Any?>): String? {
        val id = firstId(map, NEW_MSG_ID_KEY) ?: firstId(map, MSG_ID_KEY)
        lastSysMsgId = id
        if (id == null) return null
        return MsgSnapshots.lookup(id)
    }

    /** id 可能带前缀不同的 key，统一按后缀找，再解析成 Long。 */
    private fun firstId(map: Map<String, Any?>, exactKey: String): Long? {
        val raw = map[exactKey] as? String
            ?: map.entries.firstOrNull { it.key.endsWith("newmsgid") }?.value as? String
            ?: map.entries.firstOrNull { it.key.endsWith("msgid") && !it.key.endsWith("newmsgid") }?.value as? String
        return raw?.trim()?.toLongOrNull()
    }

    private fun senderOfSysMsg(map: Map<String, Any?>): String? {
        val replaceKey = map.keys.firstOrNull { it.endsWith("replacemsg") } ?: return null
        val original = map[replaceKey] as? String
        if (original.isNullOrBlank()) return null
        return extractSender(original)
    }

    /**
     * 从 doRevokeMsg 的参数里猜撤回者。
     *
     * 签名约 (String xml, long msgSvrId, p0, String talker, String, String)，
     * 但各版本顺序可能不同，所以只挑「像 talker 的那个」——
     * 微信的 talker 通常以 `@chatroom` 结尾或是 wxid_ 开头，或就是对方 wxid。
     * 取不到就返回 null，调用方会退回通用文案（不影响拦截本身）。
     */
    /**
     * doRevokeMsg 的参数里有个 long 型的 msgSvrId，拿它去快照表反查内容。
     *
     * 参数顺序各版本可能不同，所以「挑那个能查到内容的」——
     * 查到就是它，查不到全返回 null（不影响拦截）。
     */
    private fun contentFromArgs(args: Array<Any?>?): String? {
        if (args == null) return null
        for (a in args) {
            val id = when (a) {
                is Long -> a
                is java.lang.Long -> a.toLong()
                else -> continue
            }
            if (id <= 0L) continue
            MsgSnapshots.lookup(id)?.let { return it }
        }
        return null
    }

    private fun senderFromArgs(args: Array<Any?>?): String? {
        if (args == null) return null
        for (a in args) {
            val s = a as? String ?: continue
            if (s.isBlank() || s.length > 64) continue
            // 排除掉明显是 XML 的参数
            if (s.trimStart().startsWith("<")) continue
            if (s.endsWith("@chatroom")) return "群聊"
            if (s.startsWith("wxid_")) return s
            // 兜底：第一个非 XML 的短字符串
            return s
        }
        return null
    }

    /**
     * 从微信原始的 replacemsg 里抠出撤回者名字。
     *
     * 实际见过三种形态，都要兼容：
     *  - `"张三" 撤回了一条消息`（带引号）
     *  - `你 撤回了一条消息`
     *  - 个别版本不带引号直接是昵称
     */
    private fun extractSender(replacemsg: String): String {
        val quoted = QUOTED.find(replacemsg)?.groupValues?.getOrNull(1)
        if (!quoted.isNullOrBlank()) return quoted

        val before = replacemsg.substringBefore("撤回").trim()
        if (before.isNotBlank()) return before

        return replacemsg.trim()
    }

    private val QUOTED = Regex("[\"'「『《](.+?)[\"'」』》]")
}
