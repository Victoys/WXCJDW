package dev.mm.wxcj

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * 把模块的配置暴露给宿主（微信）进程读取。
 *
 * ## 为什么需要它
 *
 * 原来宿主侧用 `XSharedPreferences` 读模块写在自己 data 目录下的 XML：
 *
 * ```
 * /data/data/dev.mm.wxcj/shared_prefs/wxcj_prefs.xml
 * ```
 *
 * Android 10 起 `/data/data/<包名>` 目录本身是 **700 (rwx------)**，
 * 微信是另一个 uid，**连目录都进不去** —— 文件 chmod 成 644 也没用，父目录就把访问挡住了。
 * 结果就是：宿主一个配置都读不到，全部 silently 走默认值。
 *
 * 这正是「设置页 6 个开关全开，但提示只显示 2 个功能（恰好等于默认值）」的原因。
 *
 * ## 做法
 *
 * ContentProvider 走 Binder IPC，由系统以模块身份代理读取，不受文件权限限制。
 * exported=true 是必须的：否则微信进程无权访问。
 */
class PrefsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(Prefs.FILE_NAME, android.content.Context.MODE_PRIVATE)

        val cursor = MatrixCursor(arrayOf(Prefs.COL_KEY, Prefs.COL_TYPE, Prefs.COL_VALUE))
        val all = prefs.all

        // selectionArgs 传要读的 key；不传就返回全部
        val keys = selectionArgs?.toList() ?: all.keys.toList()

        for (key in keys) {
            val value = all[key] ?: continue
            when (value) {
                is Boolean -> cursor.addRow(arrayOf(key, "b", if (value) "1" else "0"))
                is Int -> cursor.addRow(arrayOf(key, "i", value.toString()))
                is Float -> cursor.addRow(arrayOf(key, "f", value.toString()))
                is Long -> cursor.addRow(arrayOf(key, "l", value.toString()))
                is String -> cursor.addRow(arrayOf(key, "s", value))
                else -> cursor.addRow(arrayOf(key, "s", value.toString()))
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.${Prefs.AUTHORITY}.prefs"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
