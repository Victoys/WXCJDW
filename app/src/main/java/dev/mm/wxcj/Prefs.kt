package dev.mm.wxcj

import android.content.Context
import android.content.Intent
import android.net.Uri
import de.robv.android.xposed.XSharedPreferences

/**
 * 模块侧（本 APK 自己）与宿主侧（微信进程）共用的一套开关与参数。
 *
 * ## 读取为什么不稳定
 *
 * 宿主进程读模块配置只有两条路：
 *
 *  1. `XSharedPreferences` —— 读 `/data/data/dev.mm.wxcj/shared_prefs/`，
 *     Android 10+ 该目录是 700，微信进不去，基本不可用（保留作回退）。
 *  2. `ContentProvider`（见 PrefsProvider）—— 走 Binder，由系统代理读取。
 *     但它依赖**模块进程能被拉起来**：首次 query 时系统要启动模块进程，
 *     若被 ROM 的省电/冻结策略挡住、或启动慢，就会拿到 null。
 *     这就是「不是每次冷启动都能读到配置」的原因。
 *
 * 因此加了第三条：**宿主侧镜像**。每次成功读到真实配置后，往宿主自己的
 * data 目录写一份；下次冷启动若 Provider 暂时不可用，先用镜像启动功能，
 * 再由后台线程重试刷新。
 *
 * 读取顺序：Provider → XSharedPreferences → 镜像 → 默认值。
 */
object Prefs {

    /** 与 app/build.gradle.kts 的 applicationId 保持一致 */
    const val MODULE_PACKAGE = "dev.mm.wxcj"

    /** 宿主包名，用于给微信进程发显式广播 */
    const val WECHAT_PACKAGE = "com.tencent.mm"

    const val FILE_NAME = "wxcj_prefs"

    /** ContentProvider 的 authority，Manifest 中必须同名 */
    const val AUTHORITY = "$MODULE_PACKAGE.prefs"

    /** 微信 → 模块：唤醒模块进程（见 WakeReceiver） */
    const val ACTION_WAKE = "$MODULE_PACKAGE.WAKE"

    /** 模块 → 微信：设置页改了开关，宿主应立即重读并热装载 */
    const val ACTION_PREFS_CHANGED = "$MODULE_PACKAGE.PREFS_CHANGED"

    /** 微信 → 模块：在微信地图里选完点，把坐标回传给模块进程落盘 */
    const val ACTION_LOCATION_PICKED = "$MODULE_PACKAGE.LOCATION_PICKED"

    const val EXTRA_LAT = "lat"
    const val EXTRA_LNG = "lng"
    const val EXTRA_ENABLE = "enable"

    const val COL_KEY = "key"
    const val COL_TYPE = "type"
    const val COL_VALUE = "value"

    // ---- 开关 ----
    const val KEY_ANTI_RECALL = "anti_recall"
    const val KEY_DISABLE_TYPING = "disable_typing"
    const val KEY_DISABLE_PAT = "disable_pat"

    // ---- 虚拟定位 ----
    const val KEY_FAKE_LOCATION = "fake_location"
    /** 纬度，存字符串：空/非法时回落到默认值，避免 Float 精度在 XML 里来回转换丢位 */
    const val KEY_FAKE_LAT = "fake_lat"
    const val KEY_FAKE_LNG = "fake_lng"
    /** 随机抖动半径（米），0 = 关闭 */
    const val KEY_FAKE_JITTER = "fake_jitter"

    // ---- 防撤回提示 ----
    /** true = 拦截后在聊天里留下一条自定义提示；false = 静默拦截（原行为） */
    const val KEY_RECALL_NOTICE = "recall_notice"
    /** 提示模板，$sender 会替换成撤回者的名字 */
    const val KEY_RECALL_NOTICE_TEXT = "recall_notice_text"
    /** true = 拦截提示常驻，必须手动点「知道了」才消失；false = 3 秒自动消失 */
    const val KEY_RECALL_CONFIRM = "recall_confirm"
    /** true = 也拦截/提示自己撤回的消息；false = 只管对方撤回的 */
    const val KEY_RECALL_SELF = "recall_self"

    const val KEY_STARTUP_NOTICE = "startup_notice"
    const val KEY_DIAGNOSTIC = "diagnostic"
    const val KEY_RESCAN_TOKEN = "rescan_token"

    private val ALL_KEYS = arrayOf(
        KEY_ANTI_RECALL, KEY_DISABLE_TYPING, KEY_DISABLE_PAT,
        KEY_RECALL_NOTICE, KEY_RECALL_NOTICE_TEXT, KEY_RECALL_CONFIRM, KEY_RECALL_SELF,
        KEY_FAKE_LOCATION, KEY_FAKE_LAT, KEY_FAKE_LNG, KEY_FAKE_JITTER,
        KEY_STARTUP_NOTICE, KEY_DIAGNOSTIC, KEY_RESCAN_TOKEN,
    )

    /** 镜像读写需要知道每个 key 的类型 */
    private val TYPES = mapOf(
        KEY_ANTI_RECALL to "b",
        KEY_DISABLE_TYPING to "b",
        KEY_DISABLE_PAT to "b",
        KEY_RECALL_NOTICE to "b",
        KEY_RECALL_NOTICE_TEXT to "s",
        KEY_RECALL_CONFIRM to "b",
        KEY_RECALL_SELF to "b",
        KEY_FAKE_LOCATION to "b",
        KEY_FAKE_LAT to "s",
        KEY_FAKE_LNG to "s",
        KEY_FAKE_JITTER to "i",
        KEY_STARTUP_NOTICE to "b",
        KEY_DIAGNOSTIC to "b",
        KEY_RESCAN_TOKEN to "i",
    )

    const val DEFAULT_ANTI_RECALL = true
    const val DEFAULT_DISABLE_TYPING = true
    const val DEFAULT_DISABLE_PAT = false
    const val DEFAULT_RECALL_NOTICE = false
    const val DEFAULT_RECALL_CONFIRM = true
    const val DEFAULT_RECALL_SELF = false

    const val DEFAULT_RECALL_NOTICE_TEXT = "已拦截「\$sender」撤回的消息"

    const val DEFAULT_STARTUP_NOTICE = true
    const val DEFAULT_DIAGNOSTIC = false

    // ---- 虚拟定位默认值（与 WeKit 的默认值一致：上海人民广场附近）----
    const val DEFAULT_FAKE_LOCATION = false
    const val DEFAULT_FAKE_LAT = "31.224361"
    const val DEFAULT_FAKE_LNG = "121.469170"
    const val DEFAULT_FAKE_JITTER = 0

    // ---- 来源标记 ----
    const val SOURCE_PROVIDER = "ContentProvider"
    const val SOURCE_XSHARED = "XSharedPreferences"
    const val SOURCE_MIRROR = "宿主侧镜像"
    const val SOURCE_DEFAULT = "默认值（读取失败）"

    /** 模块自身进程调用：读写设置页的开关。 */
    fun local(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** 宿主（微信）进程调用：只读。读取顺序见类注释。 */
    fun remote(context: Context?): Snapshot {
        if (context != null) {
            // 关键：先把模块进程拉起来，否则 Provider 大概率返回 null
            wake(context)
            val p = queryWithRetry(context, times = 4, baseDelayMs = 250L)
            if (!p.isNullOrEmpty()) return Snapshot(SOURCE_PROVIDER, p)
            if (p != null) Logger.w("Prefs", "ContentProvider 连通但无数据（设置页从未打开过？）")
        }

        val x = tryXShared()
        if (x.isNotEmpty()) return Snapshot(SOURCE_XSHARED, x)

        val mirror = context?.let { Mirror.load(it) }
        if (!mirror.isNullOrEmpty()) {
            Logger.w("Prefs", "Provider 暂不可用，先用宿主侧镜像启动（后台继续重试）")
            return Snapshot(SOURCE_MIRROR, mirror)
        }

        Logger.e("Prefs", "所有读取方式都失败，将全部使用默认值")
        return Snapshot(SOURCE_DEFAULT, emptyMap())
    }

    /**
     * 后台重试读取真实配置。成功时返回快照（调用方应写入镜像）。
     * 只能在**非主线程**调用（内部会 sleep）。
     */
    fun refresh(context: Context?): Snapshot? {
        if (context == null) return null
        val p = queryWithRetry(context, times = 8, baseDelayMs = 800L)
        if (!p.isNullOrEmpty()) {
            Logger.i("Prefs", "重试成功：读到 ${p.size} 项")
            return Snapshot(SOURCE_PROVIDER, p)
        }
        val x = tryXShared()
        if (x.isNotEmpty()) return Snapshot(SOURCE_XSHARED, x)
        Logger.w("Prefs", "重试 8 次仍未读到配置")
        return null
    }

    /** 把已读到的配置写到宿主自己的目录，供下次冷启动兜底。 */
    fun mirrorSave(context: Context?, snapshot: Snapshot) {
        if (context == null || snapshot.isEmpty()) return
        if (snapshot.source == SOURCE_MIRROR || snapshot.source == SOURCE_DEFAULT) return
        runCatching { Mirror.save(context, snapshot.dump()) }
            .onFailure { Logger.w("Prefs", "写镜像失败：${it.message}") }
    }

    /**
     * 发显式广播唤醒模块进程。
     *
     * [Intent.FLAG_INCLUDE_STOPPED_PACKAGES] 是重点：默认广播不会投递给「已停止」
     * 的包，而从未启动过的模块 App 正是这种状态，不加这个 flag 唤醒不生效。
     */
    private fun wake(context: Context) {
        runCatching {
            val intent = Intent(ACTION_WAKE).setPackage(MODULE_PACKAGE)
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            context.sendBroadcast(intent)
            Logger.i("Prefs", "已发送唤醒广播")
        }.onFailure { Logger.w("Prefs", "发送唤醒广播失败：${it.message}") }
    }

    /**
     * 反复尝试读 Provider。模块进程从被拉起到 Provider 可用需要时间，
     * 单次查询几乎必然失败，必须重试；间隔递增，总时长约几十秒。
     */
    private fun queryWithRetry(
        context: Context,
        times: Int,
        baseDelayMs: Long,
    ): Map<String, Any?>? {
        var lastEmpty = false
        for (i in 0 until times) {
            if (i > 0) {
                runCatching { Thread.sleep(baseDelayMs * (1L shl (i - 1).coerceAtMost(4))) }
                if (i % 3 == 0) wake(context)   // 隔几次再补一发，防止模块进程又被回收
            }
            val p = runCatching { queryProvider(context) }
                .onFailure { Logger.w("Prefs", "第 ${i + 1} 次查询失败：${it.message}") }
                .getOrNull()
            if (!p.isNullOrEmpty()) {
                Logger.i("Prefs", "第 ${i + 1} 次查询成功，读到 ${p.size} 项")
                return p
            }
            lastEmpty = p != null
        }
        // 返回 emptyMap 表示「连上了但没数据」，null 表示「连不上」，调用方据此区分
        return if (lastEmpty) emptyMap() else null
    }

    private fun tryXShared(): Map<String, Any?> = runCatching {
        val sp = XSharedPreferences(MODULE_PACKAGE, FILE_NAME)
        runCatching { sp.makeWorldReadable() }
        sp.reload()
        val out = HashMap<String, Any?>()
        for (key in ALL_KEYS) {
            if (!sp.contains(key)) continue
            out[key] = sp.all[key]
        }
        out
    }.onFailure { Logger.w("Prefs", "XSharedPreferences 读取失败：${it.message}") }
        .getOrDefault(emptyMap())

    private fun queryProvider(context: Context): Map<String, Any?>? {
        val uri = Uri.parse("content://$AUTHORITY/prefs")
        val cursor = context.contentResolver.query(uri, null, null, ALL_KEYS, null)
            ?: return null
        cursor.use { c ->
            val ki = c.getColumnIndex(COL_KEY)
            val ti = c.getColumnIndex(COL_TYPE)
            val vi = c.getColumnIndex(COL_VALUE)
            if (ki < 0 || ti < 0 || vi < 0) return null

            val out = HashMap<String, Any?>()
            while (c.moveToNext()) {
                val key = c.getString(ki)
                val type = c.getString(ti)
                val raw = c.getString(vi)
                out[key] = when (type) {
                    "b" -> raw == "1"
                    "i" -> raw?.toIntOrNull()
                    "f" -> raw?.toFloatOrNull()
                    "l" -> raw?.toLongOrNull()
                    else -> raw
                }
            }
            return out
        }
    }

    /** 宿主进程自己的一份配置副本。 */
    private object Mirror {
        private const val FILE = "wxcj_prefs_mirror"

        fun load(context: Context): Map<String, Any?>? {
            val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            if (!sp.contains("_saved")) return null
            val out = HashMap<String, Any?>()
            for ((key, type) in TYPES) {
                when (type) {
                    "b" -> if (sp.contains(key)) out[key] = sp.getBoolean(key, false)
                    "i" -> if (sp.contains(key)) out[key] = sp.getInt(key, 0)
                    "s" -> sp.getString(key, null)?.let { out[key] = it }
                }
            }
            // Map.ifEmpty 的默认值必须也是 Map，不能是 null —— 这里要显式返回可空
            return if (out.isEmpty()) null else out
        }

        fun save(context: Context, values: Map<String, Any?>) {
            val editor = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            editor.clear()
            editor.putBoolean("_saved", true)
            for ((key, value) in values) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is String -> editor.putString(key, value)
                }
            }
            editor.apply()
        }
    }

    /** 一次读到的配置快照。[source] 说明值到底从哪来的。 */
    class Snapshot(val source: String, private val values: Map<String, Any?>) {

        fun isEmpty(): Boolean = values.isEmpty()

        fun has(key: String): Boolean = values.containsKey(key)

        /** 镜像写入需要原始值 */
        fun dump(): Map<String, Any?> = values

        fun getBoolean(key: String, default: Boolean): Boolean =
            (values[key] as? Boolean) ?: (values[key] as? String)?.toBoolean() ?: default

        fun getInt(key: String, default: Int): Int =
            (values[key] as? Int) ?: (values[key] as? String)?.toIntOrNull() ?: default

        fun getFloat(key: String, default: Float): Float =
            (values[key] as? Float) ?: (values[key] as? String)?.toFloatOrNull() ?: default

        fun getString(key: String, default: String): String =
            (values[key] as? String) ?: default
    }
}
