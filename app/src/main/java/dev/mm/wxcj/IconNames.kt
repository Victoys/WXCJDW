package dev.mm.wxcj

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

/**
 * 反查一个图标 View 用的**资源名**。
 *
 * 为什么靠资源名认图标：微信的类名、字段名全被混淆，唯一稳定的是
 * **资源名不参与混淆**（`panel_icon_location` 从很早的版本用到现在）。
 *
 * 做法：取 ImageView 上 drawable 的所有 int 字段，逐个用
 * `Resources.getResourceEntryName` 反查；drawable 内部通常持有资源 id。
 * 一路往父类找，因为资源 id 字段可能在父类里。
 */
object IconNames {

    /** 反查单个 drawable 对应的资源名（可能有多个，取得到几个算几个）。 */
    fun of(resources: android.content.res.Resources, drawable: Drawable?): Set<String> {
        val out = LinkedHashSet<String>()
        var cls: Class<*>? = drawable?.javaClass
        while (cls != null) {
            for (field in cls.declaredFields) {
                if (field.type != Int::class.javaPrimitiveType) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(drawable) as? Int
                }.getOrNull() ?: continue
                if (value == 0) continue
                runCatching { resources.getResourceEntryName(value) }
                    .getOrNull()?.let { out += it }
            }
            cls = cls.superclass
        }
        return out
    }

    /**
     * 这个 View **自己**用的图标资源名（不含子 View）。
     *
     * 三种来源都试：ImageView 的前景图、任何 View 的背景、
     * 以及 TextView 的 compound drawable —— 微信的「+」可能是这三种里的任意一种
     * （`ActionMenuItemView` 就是 TextView，图标挂在 compound drawable 上）。
     */
    fun ofView(view: View): Set<String> {
        val res = view.resources ?: return emptySet()
        val out = LinkedHashSet<String>()
        if (view is ImageView) out += of(res, view.drawable)
        out += of(res, view.background)
        if (view is android.widget.TextView) {
            out += of(res, view.compoundDrawables.getOrNull(0))
            out += of(res, view.compoundDrawables.getOrNull(1))
            out += of(res, view.compoundDrawables.getOrNull(2))
            out += of(res, view.compoundDrawables.getOrNull(3))
        }
        return out
    }

    /** 这个 View 及其子 View 里所有图标的资源名（用于认面板格子这种容器）。 */
    fun ofTree(root: View): Set<String> {
        val out = LinkedHashSet<String>()
        fun collect(view: View) {
            if (view.visibility != View.VISIBLE) return
            out += ofView(view)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
        }
        collect(root)
        return out
    }
}
