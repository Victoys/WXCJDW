package dev.mm.wxcj

import android.content.Context

/**
 * DexKit 解析结果缓存。
 *
 * 扫一遍微信（几百 MB、多个 dex）要几十秒到几分钟，不可能每次冷启动都重来。
 * 解析成功后把每个目标的「类名|方法名|签名」存进**宿主进程自己的** SharedPreferences
 * （注意不是模块的那份：模块的那份宿主只读，写不进去）。
 * 微信版本变了就整体失效重扫。
 */
object DexCache {

    // 改定位实现后请递增此版本号：旧缓存会自然失效，避免一直命中错误的解析结果
    // 定位目标变化（v3: 移除朋友圈自动播放；v4: 新增 doRevokeMsg；v5: 新增虚拟定位 3 个回调）
    private const val FILE = "wxcj_dex_v5"
    private const val KEY_HOST = "hostVersion"
    private const val KEY_TOKEN = "rescanToken"

    fun load(context: Context, hostVersion: Int, rescanToken: Int): Map<String, String>? {
        val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (sp.getInt(KEY_HOST, -1) != hostVersion) {
            Logger.i("DexCache", "宿主版本变化，缓存作废")
            return null
        }
        if (sp.getInt(KEY_TOKEN, -1) != rescanToken) {
            Logger.i("DexCache", "收到新的重扫令（token=$rescanToken），缓存作废")
            return null
        }
        val out = LinkedHashMap<String, String>()
        sp.all.forEach { (key, value) ->
            if (key != KEY_HOST && key != KEY_TOKEN && value is String && value.contains("|")) out[key] = value
        }
        return if (out.isEmpty()) null else out
    }

    fun save(context: Context, hostVersion: Int, entries: Map<String, String>, rescanToken: Int) {
        runCatching {
            val editor = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            editor.clear()
            editor.putInt(KEY_HOST, hostVersion)
            editor.putInt(KEY_TOKEN, rescanToken)
            entries.forEach { (key, value) -> editor.putString(key, value) }
            editor.apply()
        }.onFailure { Logger.w("DexCache", "写入缓存失败：${it.message}") }
    }

    /** 排查用：主动清空，下次启动强制重新解析 */
    fun clear(context: Context) {
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply() }
    }
}
