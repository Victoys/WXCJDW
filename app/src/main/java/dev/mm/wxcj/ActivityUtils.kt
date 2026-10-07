package dev.mm.wxcj

import android.app.Activity

/**
 * 取当前处于前台的 Activity。
 *
 * 长按面板图标 / 设置页点击时都要一个 Activity 才能 `startActivityForResult`，
 * 而回调里拿到的 Context 未必是 Activity（微信到处包 ContextWrapper）。
 *
 * 走 `ActivityThread.mActivities`：官方 API 没有「取当前 Activity」的入口，
 * 而这是个纯 Java 层字段，反射读它不涉及任何隐藏 API 限制。
 */
object ActivityUtils {

    private const val TAG = "ActivityUtils"

    /** 最近一个未 paused 的 Activity；拿不到返回 null。 */
    fun topActivity(): Activity? = runCatching {
        val atClass = Class.forName("android.app.ActivityThread")
        val current = atClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }
        val thread = current.invoke(null) ?: return null

        val field = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val records = field.get(thread) as? Map<Any, Any> ?: return null

        for (record in records.values) {
            val recordClass = record.javaClass
            val activityField = recordClass.getDeclaredField("activity").apply { isAccessible = true }
            val activity = activityField.get(record) as? Activity ?: continue
            if (activity.isFinishing || activity.isDestroyed) continue

            // 优先返回未暂停的；实在没有未暂停的就退而求其次返回最后一个
            val pausedField = runCatching {
                recordClass.getDeclaredField("paused").apply { isAccessible = true }
            }.getOrNull()
            val paused = pausedField?.getBoolean(record) ?: false
            if (!paused) return activity
        }
        null
    }.onFailure { Logger.w(TAG, "读取前台 Activity 失败：${it.message}") }.getOrNull()

    /**
     * 把任意 Context 提成一个可用的 Activity。
     *
     * 先剥掉 ContextWrapper 看它本身是不是 Activity，不是再去问 [topActivity]。
     */
    fun asActivity(context: android.content.Context?): Activity? {
        var ctx = context
        var depth = 0
        while (ctx is android.content.ContextWrapper && depth < 10) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
            depth++
        }
        if (ctx is Activity) return ctx
        return topActivity()
    }
}
