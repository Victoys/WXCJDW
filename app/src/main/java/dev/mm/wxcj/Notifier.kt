package dev.mm.wxcj

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.LinkedList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 宿主进程内的可见提示。
 *
 * 三条设计要点，每一条都是踩过的坑：
 *
 * 1. **提示往往早于第一个 Activity**：解析发生在 Application.attach 阶段，
 *    那时还没有窗口。不能「没窗口就立刻降级成 Toast」，否则浮层永远用不上。
 *    正确做法是留在队列里，等 Activity.onResume 再来取。
 *
 * 2. **必须逐条顺序显示**：多条同时贴到同一个 DecorView 上会完全重叠，
 *    用户只看得到最后一条 —— 之前就是这个 bug 导致诊断信息全被吞掉。
 *
 * 3. **浮层 vs Toast 本身就是诊断信号**：能出现黑色圆角浮层，说明
 *    `Activity.onResume` 这个 hook 成功了，Xposed 注入链路是通的；
 *    只出现系统 Toast，说明没拿到前台 Activity（微信在后台）或 hook 未生效。
 *
 * ## 两类提示
 *
 *  - **[notify]**：3 秒自动消失，用于状态类信息，不打断用户。
 *  - **[confirm]**：**不会自动消失**，必须手动点「知道了」——用于撤回拦截这类
 *    错过就看不到的信息。锁屏期间产生的也会一直排队，解锁回到微信再弹出。
 */
object Notifier {

    private const val TAG = "Notifier"
    private const val DISPLAY_MS = 3_000L
    private const val FADE_IN_MS = 200L
    private const val FADE_OUT_MS = 300L
    private const val WAIT_ACTIVITY_MS = 15_000L

    /** confirm 队列上限：极端情况（群里疯狂撤回）不至于无限堆积 */
    private const val MAX_CONFIRM = 20

    private val queue = LinkedList<String>()
    private val confirmQueue = LinkedList<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val hooked = AtomicBoolean(false)
    private val timeoutScheduled = AtomicBoolean(false)

    /** 是否正在显示某条提示；用于串行化，避免多条叠在一起。 */
    @Volatile
    private var showing = false

    /** Activity.onResume 的 hook 是否真的被回调过 —— 用来判断 Xposed 链路是否通。 */
    @Volatile
    var activityHookConfirmed = false
        private set

    /** 首次拿到前台 Activity 的信号，供诊断等待用。 */
    private val foregroundLatch = CountDownLatch(1)

    /**
     * 等到微信真正进入前台再返回。
     *
     * 诊断必须在**有前台界面之后**统计才有意义：微信在后台时所有 hook 计数都是 0，
     * 会让人误判成「hook 未生效」。
     *
     * @return 是否等到了；false = 超时仍未进前台
     */
    fun waitForForeground(timeoutMs: Long): Boolean {
        if (activityHookConfirmed) return true
        return runCatching { foregroundLatch.await(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var currentActivity: WeakReference<Activity>? = null

    /** 当前浮层视图与其宿主 Activity：宿主被销毁后必须强制回收，否则队列会被永久卡死。 */
    @Volatile
    private var overlayView: WeakReference<View>? = null

    @Volatile
    private var overlayHost: WeakReference<Activity>? = null

    /** 在拿到宿主 Context 后调用一次。 */
    fun attach(context: Context?, lpparam: XC_LoadPackage.LoadPackageParam) {
        val app = context?.applicationContext
        appContext = app
        if (!hooked.compareAndSet(false, true)) return

        // 方案一（主）：官方 ActivityLifecycleCallbacks。
        // 3.5 只靠 hook Activity.onResume，实测微信在前台 3 分钟仍反复「等待前台窗口超时」，
        // 说明那条路径没拿到可用的 Activity。官方回调由系统直接分发，更可靠。
        val application = app as? Application
        if (application != null) {
            runCatching {
                application.registerActivityLifecycleCallbacks(
                    object : Application.ActivityLifecycleCallbacks {
                        override fun onActivityResumed(activity: Activity) = setForeground(activity)
                        override fun onActivityDestroyed(activity: Activity) {
                            if (currentActivity?.get() === activity) {
                                currentActivity = null
                                Logger.i(TAG, "前台 Activity 已销毁，清空引用")
                            }
                        }
                        override fun onActivityCreated(a: Activity, b: android.os.Bundle?) = Unit
                        override fun onActivityStarted(a: Activity) = Unit
                        override fun onActivityPaused(a: Activity) = Unit
                        override fun onActivityStopped(a: Activity) = Unit
                        override fun onActivitySaveInstanceState(a: Activity, o: android.os.Bundle) = Unit
                    },
                )
                lifecycleRegistered = true
                Logger.i(TAG, "已注册 ActivityLifecycleCallbacks")
            }.onFailure { Logger.e(TAG, "注册 ActivityLifecycleCallbacks 失败", it) }
        } else {
            Logger.w(TAG, "拿不到 Application，只能用 hook 兜底")
        }

        // 方案二（备）：Xposed hook，两者任一命中即可
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                lpparam.classLoader,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? Activity ?: return
                        setForeground(act)
                    }
                },
            )
            Logger.i(TAG, "已 hook Activity.onResume（备用）")
        }.onFailure { Logger.e(TAG, "hook Activity.onResume 失败", it) }
    }

    /** 记录前台 Activity 并立刻尝试出队显示。 */
    private fun setForeground(act: Activity) {
        if (act.isFinishing || act.isDestroyed) return
        activityHookConfirmed = true
        foregroundLatch.countDown()
        currentActivity = WeakReference(act)
        releaseStaleOverlay()
        flush()
    }

    @Volatile
    private var lifecycleRegistered = false

    /** 最近一次 flush 失败的原因，诊断用。 */
    @Volatile
    private var lastFlushFail: String = ""

    /** 诊断：为什么浮层出不来。 */
    fun flushDiagnostics(): String =
        "浮层：lifecycle=${if (lifecycleRegistered) "已注册" else "未注册"}，" +
            "hookConfirmed=$activityHookConfirmed，showing=$showing，" +
            "待显示=${queue.size}条/待确认=${confirmQueue.size}条" +
            (if (lastFlushFail.isNotBlank()) "，最近失败：$lastFlushFail" else "")

    /**
     * 宿主 Activity 已被销毁（用户退出该页 / 微信被杀）时，
     * 之前挂上去的浮层视图会随 DecorView 一起失效，但 [showing] 仍是 true，
     * 导致后续所有提示再也出不来 —— 这里强制回收。
     */
    private fun releaseStaleOverlay() {
        if (!showing) return
        val host = overlayHost?.get()
        if (host != null && !host.isFinishing && !host.isDestroyed) return
        Logger.w(TAG, "回收孤儿浮层（宿主已销毁），恢复提示队列")
        runCatching {
            overlayView?.get()?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        }
        overlayView = null
        overlayHost = null
        showing = false
    }

    /** 发一条自动消失的提示。可在任意线程调用。 */
    fun notify(text: String) {
        Logger.i(TAG, "提示：$text")
        handler.post {
            queue.add(text)
            scheduleTimeout()
            flush()
        }
    }

    /**
     * 发一条**必须手动确认**的提示。可在任意线程调用。
     *
     * 不会自动消失，也不会因为等待窗口超时而被降级成 Toast ——
     * 锁屏时产生的会一直留在队列里，等回到微信再逐条弹出。
     */
    fun confirm(text: String) {
        Logger.i(TAG, "待确认提示：$text")
        handler.post {
            while (confirmQueue.size >= MAX_CONFIRM) confirmQueue.pollFirst()
            confirmQueue.add(text)
            flush()
        }
    }

    /** 串行出队：confirm 优先，且两类不会同时显示（会互相遮挡）。 */
    private fun flush() {
        if (showing) return

        val act = currentActivity?.get()
        if (act == null) {
            lastFlushFail = "没有前台 Activity 引用（lifecycle=${if (lifecycleRegistered) "已注册" else "未注册"}，hookConfirmed=$activityHookConfirmed）"
            return
        }
        if (act.isFinishing || act.isDestroyed) {
            lastFlushFail = "前台 Activity 正在销毁"
            return
        }

        // ---- confirm 优先 ----
        if (confirmQueue.isNotEmpty()) {
            showing = true
            lastFlushFail = ""
            val remaining = confirmQueue.size
            val text = confirmQueue.poll() ?: return
            val shown = runCatching { showConfirmOverlay(act, text, remaining) }.getOrDefault(false)
            if (!shown) {
                showing = false
                fallbackToast(text)
                return
            }
            // 等用户点击，不设超时
            return
        }

        val text = queue.poll() ?: return
        showing = true
        lastFlushFail = ""

        val shown = runCatching { showOverlay(act, text) }.getOrDefault(false)
        if (!shown) {
            showing = false
            fallbackToast(text)
            return
        }

        // 本条显示结束后再排下一条
        handler.postDelayed({
            showing = false
            flush()
        }, DISPLAY_MS + FADE_OUT_MS + 100)
    }

    /**
     * 等太久仍无窗口（微信一直在后台），把**自动消失类**的提示一次性用 Toast 发出去。
     *
     * 已经拿到前台或正在显示时**不降级** —— 之前会在浮层正常显示期间仍打
     * 「等待前台窗口超时」，纯属误导。
     */
    private fun scheduleTimeout() {
        if (!timeoutScheduled.compareAndSet(false, true)) return
        if (activityHookConfirmed || showing) return
        handler.postDelayed({
            timeoutScheduled.set(false)
            if (queue.isEmpty()) return@postDelayed
            val merged = queue.joinToString(" | ")
            queue.clear()
            Logger.w(TAG, "等待前台窗口超时，降级为 Toast")
            fallbackToast(merged)
        }, WAIT_ACTIVITY_MS)
    }

    private fun fallbackToast(text: String) {
        val ctx = appContext
        if (ctx == null) {
            Logger.w(TAG, "既无前台 Activity 也无 Context，提示被丢弃：$text")
            return
        }
        runCatching { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
            .onFailure { Logger.w(TAG, "Toast 失败：${it.message}") }
    }

    private fun clearOverlay() {
        overlayView = null
        overlayHost = null
        showing = false
    }

    private fun bubble(): GradientDrawable = GradientDrawable().apply {
        setColor(0xDD000000.toInt())
        cornerRadius = 28f
    }

    /** 在 DecorView 上贴浮层；返回 false 表示没能贴上，调用方应退回 Toast。 */
    private fun showOverlay(activity: Activity, text: String): Boolean {
        val decor = activity.window?.decorView as? ViewGroup ?: return false

        val tv = TextView(activity).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(36, 22, 36, 22)
            background = bubble()
            alpha = 0f
        }

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            bottomMargin = (activity.resources.displayMetrics.density * 120).toInt()
        }

        decor.addView(tv, lp)
        overlayView = WeakReference(tv)
        overlayHost = WeakReference(activity)
        tv.animate().alpha(1f).setDuration(FADE_IN_MS).start()

        handler.postDelayed({
            runCatching {
                tv.animate().alpha(0f).setDuration(FADE_OUT_MS)
                    .withEndAction {
                        runCatching { decor.removeView(tv) }
                        clearOverlay()
                    }
                    .start()
            }.onFailure {
                runCatching { decor.removeView(tv) }
                clearOverlay()
            }
        }, DISPLAY_MS)
        return true
    }

    /**
     * 贴一个**带确认按钮**的浮层，点「知道了」才移除并显示下一条。
     *
     * @param remaining 入队时的队列长度，用于在多条堆积时显示「还有 N 条」
     */
    private fun showConfirmOverlay(activity: Activity, text: String, remaining: Int): Boolean {
        val decor = activity.window?.decorView as? ViewGroup ?: return false
        val dp = activity.resources.displayMetrics.density

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = bubble()
            setPadding((26 * dp).toInt(), (22 * dp).toInt(), (26 * dp).toInt(), (18 * dp).toInt())
            alpha = 0f
        }

        val tv = TextView(activity).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, 0, (14 * dp).toInt())
        }
        box.addView(tv)

        val btn = Button(activity).apply {
            val label = if (remaining > 1) "知道了（还有 ${remaining - 1} 条）" else "知道了"
            this.text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0x33FFFFFF)
                cornerRadius = 18f
            }
        }
        box.addView(btn)

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            bottomMargin = (dp * 110).toInt()
            leftMargin = (dp * 24).toInt()
            rightMargin = (dp * 24).toInt()
        }

        decor.addView(box, lp)
        overlayView = WeakReference(box)
        overlayHost = WeakReference(activity)
        box.animate().alpha(1f).setDuration(FADE_IN_MS).start()

        var dismissed = false
        val dismiss = {
            if (!dismissed) {
                dismissed = true
                runCatching {
                    box.animate().alpha(0f).setDuration(FADE_OUT_MS)
                        .withEndAction {
                            runCatching { decor.removeView(box) }
                            clearOverlay()
                            handler.post { flush() }
                        }
                        .start()
                }.onFailure {
                    runCatching { decor.removeView(box) }
                    clearOverlay()
                    handler.post { flush() }
                }
            }
            Unit
        }
        btn.setOnClickListener { dismiss() }
        return true
    }
}
