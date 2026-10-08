package dev.mm.wxcj

import android.view.View
import android.widget.AdapterView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 虚拟定位的选点入口：**长按聊天面板的「位置」图标**。
 *
 * 这是 WeKit 对虚拟定位用的原生入口（`FakeLocation` 的
 * `WeChatInputBarMenuApi.methodAppGridGetView` + `hookAppGridLongClick`），
 * 只依赖一个 DexKit 特征串，不往微信设置页插任何东西，风险最小。
 *
 * 链路三跳：
 *
 *  1. hook 聊天面板（AppGrid）的 `getView` —— 每次面板画一个格子都会进来；
 *  2. 认出哪个格子是「位置」—— 靠图标资源名 `panel_icon_location`
 *     （类名/字段名全被混淆，但**资源名不参与混淆**，是唯一稳定的锚点）；
 *  3. hook 面板的长按监听 —— 长按的正好是位置格子时，拉起微信自己的地图选点页。
 *
 * 长按其它格子照常走微信自己的逻辑，完全不受影响。
 */
object WeChatLocationEntry {

    private const val TAG = "LocEntry"

    /** 微信面板里「位置」图标的资源名（不参与混淆，跨版本稳定） */
    private const val ICON_NAME = "panel_icon_location"

    /** 认出来的「位置」格子（弱引用，View 回收后自动失效） */
    private val locationViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /** 已 hook 过的长按监听类，避免同一个类重复挂载 */
    private val hookedListeners = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var panelHooked = false
        private set

    /** 入口触发次数（诊断用：区分「没挂上」还是「挂上了但没识别到位置格子」） */
    @Volatile
    var longClickSeen = 0
        private set

    @Volatile
    var pickLaunched = 0
        private set

    /** 长按了位置格子但开关关着、已放行的次数（诊断用） */
    @Volatile
    var longClickSkipped = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    /**
     * hook 聊天面板的 `getView`。
     *
     * @param methods DexKit 定位到的 getView 候选
     * @param classLoader 承载微信业务类的真实 ClassLoader（找选点页要用）
     * @return 成功挂载的方法数
     */
    fun install(methods: List<Method>, classLoader: ClassLoader): Int {
        if (methods.isEmpty()) {
            Logger.w(TAG, "面板入口未装载：AppGrid.getView 没定位到")
            return 0
        }
        var n = 0
        methods.forEach { method ->
            runCatching { hookGetView(method, classLoader) }
                .onSuccess { n++ }
                .onFailure { Logger.e(TAG, "hook getView 失败", it) }
        }
        if (n == 0) {
            Logger.w(TAG, "面板入口未装载（getView 全部挂载失败）")
            return 0
        }
        panelHooked = true
        Logger.i(TAG, "面板入口已挂载（$n 处）")
        return n
    }

    private fun hookGetView(method: Method, classLoader: ClassLoader) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val view = param.result as? View ?: return
                    val isLocation = isLocationItem(view)
                    synchronized(locationViews) {
                        if (isLocation) locationViews.add(view) else locationViews.remove(view)
                    }
                    if (!isLocation) return

                    // args[2] 是父容器（AdapterView），长按监听挂在它上面
                    val grid = param.args.getOrNull(2) as? AdapterView<*> ?: return
                    val listener = grid.onItemLongClickListener ?: return
                    hookLongClick(listener, classLoader)
                }.onFailure { Logger.w(TAG, "识别面板格子失败：${it.message}") }
            }
        })
        Logger.i(TAG, "已挂载：${method.declaringClass.name}.${method.name}")
    }

    /**
     * 认出「位置」格子：看它（含子 View）里有没有 `panel_icon_location` 这个图标。
     *
     * 反查资源名的活儿交给 [IconNames.ofTree]（与「长按主界面+」那个入口共用）。
     */
    private fun isLocationItem(item: View): Boolean {
        val names = IconNames.ofTree(item)
        return names.any { it.equals(ICON_NAME, ignoreCase = true) }
    }

    private fun hookLongClick(listener: AdapterView.OnItemLongClickListener, classLoader: ClassLoader) {
        val clazz = listener.javaClass
        if (!hookedListeners.add(clazz.name)) return

        val method = runCatching {
            XposedHelpers.findMethodExact(
                clazz,
                "onItemLongClick",
                AdapterView::class.java,
                View::class.java,
                Integer.TYPE,
                java.lang.Long.TYPE,
            )
        }.onFailure {
            lastError = "找不到 onItemLongClick：${it.message}"
            Logger.w(TAG, "找不到 onItemLongClick：${it.message}")
        }.getOrNull() ?: return

        runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val view = param.args.getOrNull(1) as? View ?: return
                        val isLocation = synchronized(locationViews) { view in locationViews }
                        // 长按的不是位置格子：照常走微信自己的长按行为
                        if (!isLocation) return
                        longClickSeen++   // 只统计「长按了位置格子」

                        //
                        // 虚拟定位开关关着时**不拉起选点页**，直接走微信原本的长按行为。
                        // 这是刻意的防误触：开关一关，长按「位置」就变回普通操作。
                        //
                        // 注意要在**运行时**判断，不能只看装载时机：Xposed 的 hook
                        // 装上后无法安全卸载，若只靠「装载时开关是否打开」，
                        // 用户在设置页关掉开关后长按仍会弹出选点页。
                        // 所以入口照常无条件装载（保证开关从关→开能立刻生效），
                        // 由这里的实时判断来决定是否响应。
                        //
                        if (!FakeLocation.enabled) {
                            longClickSkipped++
                            return
                        }

                        val grid = param.args.getOrNull(0) as? AdapterView<*>
                        val context = grid?.context ?: view.context
                        if (FakeLocationPicker.launch(context, classLoader)) {
                            pickLaunched++
                            param.result = true   // 已消费，别再触发微信原本的长按
                        }
                    }.onFailure { Logger.e(TAG, "处理长按失败", it) }
                }
            })
            Logger.i(TAG, "长按入口已挂载：${clazz.name}.onItemLongClick")
        }.onFailure {
            lastError = "hook onItemLongClick 失败：${it.message}"
            Logger.e(TAG, "hook onItemLongClick 失败", it)
        }
    }

    /** 诊断用：一眼看出是「没挂上」还是「挂上了但没认出位置格子」。 */
    fun report(): String = buildString {
        append("选点入口：")
        append(if (panelHooked) "已挂载 ✓" else "未挂载 ✗")
        append(if (FakeLocation.enabled) "，开关已开" else "，开关已关（长按不响应）")
        append("，长按位置 $longClickSeen 次（忽略 $longClickSkipped 次），拉起 $pickLaunched 次")
        lastError?.let { append("（$it）") }
    }
}
