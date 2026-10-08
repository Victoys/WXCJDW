package dev.mm.wxcj

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap

/**
 * 选点入口（主入口）：**长按微信主界面右上角的「+」**。
 *
 * 比「进聊天 → 开面板 → 长按位置」少三步，而且不用先找个对话。
 *
 * ## 怎么认出那个「+」
 *
 * 微信主界面右上角的「+」是 ActionBar/Toolbar 里的一个图标按钮，类名和 id 都
 * 被混淆。这里不赌具体资源名，而是**按位置 + 图标名加权**打分：
 *
 *  1. 位置：屏幕顶部 1/4 以内、右侧 45% 以内 —— 这个区域通常只有那一个可点图标；
 *  2. 资源名含 `add` / `plus` / `more` / `menu` 的加权；
 *  3. 资源名含 `search` / `scan` / `back` / `close` 的直接排除（可能是旁边的搜索/扫一扫）；
 *  4. 可点击的加权。
 *
 * 打分制的好处是：即使微信换了资源名，只要位置对也能命中；
 * 旁边真有搜索图标时，靠名字把它压下去。命中的资源名会写进诊断，
 * 万一认错了，把诊断里那串名字发过来就能精确定位。
 *
 * 只挂 `setOnLongClickListener`，不改点击行为 —— 单击「+」照常弹微信的菜单。
 */
object WeChatMainPlusEntry {

    private const val TAG = "MainPlus"

    /** 命中资源名里的这些词 → 加分（说明它大概率就是「加号/更多」类图标） */
    private val POSITIVE_WORDS = listOf("add", "plus", "more", "menu", "create", "new")

    /** 命中这些词 → 直接排除（是旁边的搜索/扫一扫/返回/关闭） */
    private val NEGATIVE_WORDS = listOf("search", "scan", "back", "close", "voice", "setting")

    /** 已挂过长按的 View（弱引用，View 回收后自动失效） */
    private val attached = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    @Volatile
    var launched = false
        private set

    /** 认到的那个图标的资源名（诊断用；认错时把这一串发过来即可精确修正） */
    @Volatile
    var pickedIconNames: String = "（还没扫到）"
        private set

    /** 本次扫描里出现在目标区域的候选数量（诊断用） */
    @Volatile
    var candidateCount = 0
        private set

    @Volatile
    var longClickCount = 0
        private set

    @Volatile
    var pickLaunched = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    /**
     * 挂到微信主界面。
     *
     * @param classLoaders 候选 ClassLoader，逐个试到能找到 LauncherUI 为止
     */
    fun install(classLoaders: List<ClassLoader>): Boolean {
        for (cl in classLoaders) {
            val method = runCatching {
                DexTargets.findMethodInHierarchy(
                    Class.forName("com.tencent.mm.ui.LauncherUI", false, cl), "onResume",
                )
            }.getOrNull() ?: continue

            val ok = runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        // 等布局真正画出来再扫：onResume 时 DecorView 可能还没尺寸
                        runCatching { attachOnNextFrame(activity, cl) }
                            .onFailure { Logger.w(TAG, "挂载失败：${it.message}") }
                    }
                })
                true
            }.onFailure {
                lastError = "hook LauncherUI.onResume 失败：${it.message}"
                Logger.e(TAG, "hook LauncherUI.onResume 失败", it)
            }.getOrDefault(false)

            if (ok) {
                launched = true
                Logger.i(TAG, "主界面长按入口已挂载")
                return true
            }
        }
        Logger.w(TAG, "主界面长按入口未挂载：没找到 LauncherUI.onResume")
        return false
    }

    private fun attachOnNextFrame(activity: Activity, classLoader: ClassLoader) {
        val decor = activity.window?.decorView ?: return
        if (synchronized(attached) { decor in attached }) return
        decor.post {
            runCatching {
                synchronized(attached) {
                    if (!attached.add(decor)) return@runCatching
                }
                scanAndAttach(decor, classLoader)
            }.onFailure { Logger.w(TAG, "扫描主界面失败：${it.message}") }
        }
    }

    private fun scanAndAttach(root: View, classLoader: ClassLoader) {
        val width = root.width
        val height = root.height
        if (width <= 0 || height <= 0) {
            synchronized(attached) { attached.remove(root) }   // 尺寸还没出来，下次再试
            return
        }
        val topLimit = height * 0.25f
        val rightLimit = width * 0.55f

        val location = IntArray(2)
        var best: View? = null
        var bestScore = 0
        var bestNames: Set<String> = emptySet()
        var count = 0

        fun walk(view: View) {
            if (view.visibility != View.VISIBLE) return
            val w = view.width
            val h = view.height
            if (w <= 0 || h <= 0 || w > width * 0.5f) {
                // 太宽的是标题栏/容器本身，不是图标按钮
            } else {
                view.getLocationOnScreen(location)
                val x = location[0]
                val y = location[1]
                if (y >= 0 && y <= topLimit && x >= rightLimit) {
                    count++
                    val names = IconNames.ofView(view)
                    val lower = names.joinToString(" ").lowercase()
                    if (NEGATIVE_WORDS.any { lower.contains(it) }) {
                        // 明确是搜索/扫一扫之类，跳过
                    } else {
                        var score = 1
                        if (POSITIVE_WORDS.any { lower.contains(it) }) score += 10
                        if (view.isClickable) score += 2
                        if (view is ImageView) score += 1
                        if (names.isEmpty()) score -= 1   // 读不出资源名的排后面
                        if (score > bestScore) {
                            bestScore = score
                            best = view
                            bestNames = names
                        }
                    }
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)

        candidateCount = count
        pickedIconNames = if (bestNames.isEmpty()) "（读不出资源名）" else bestNames.joinToString(",")

        val target = best
        if (target == null) {
            synchronized(attached) { attached.remove(root) }
            Logger.w(TAG, "目标区域里没找到可用图标（候选 $count 个），下次 onResume 再试")
            return
        }

        target.setOnLongClickListener {
            longClickCount++
            val ok = FakeLocationPicker.launch(it.context, classLoader)
            if (ok) pickLaunched++
            ok   // true = 消费掉，不要再触发微信自己的长按行为
        }
        Logger.i(TAG, "已挂长按：资源名=$pickedIconNames（候选 $count 个，得分 $bestScore）")
    }

    /** 诊断用 */
    fun report(): String = buildString {
        append("主界面长按+：")
        append(if (launched) "已挂载" else "未挂载")
        append("，候选 $candidateCount 个，长按 $longClickCount 次，拉起 $pickLaunched 次")
        append("，图标=$pickedIconNames")
        lastError?.let { append("（$it）") }
    }
}
