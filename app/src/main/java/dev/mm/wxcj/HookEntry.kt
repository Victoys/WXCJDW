package dev.mm.wxcj

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Xposed 入口（assets/xposed_init 中声明）。
 *
 * 本模块只做 4 个轻量功能，全部是「hook 一个返回值」，不涉及任何 UI / 菜单注入。
 *
 * 流程：
 *  1. 只处理微信主进程；
 *  2. hook Application.attach(Context) 拿到宿主 Context（比 onCreate 更早更稳）；
 *  3. 读配置（Provider → XSharedPreferences → 宿主侧镜像 → 默认值）；
 *     读到非默认值就往宿主目录写镜像，下次冷启动即使 Provider 暂时不可用也能先启动功能；
 *     若本次只拿到镜像或默认值，后台线程继续重试 Provider，成功后**热装载**缺的功能；
 *  4. 后台解析 DexKit 目标（结果按微信版本缓存）并装载 hook。
 *
 * 每个功能独立 try/catch：某一个失败不影响其他功能。
 */
class HookEntry : IXposedHookLoadPackage {

    private val installed = AtomicBoolean(false)

    /** 框架给的宿主 ClassLoader：系统层 hook（虚拟定位兜底）要用，不受 Tinker 换 ClassLoader 影响 */
    @Volatile
    private var hostClassLoader: ClassLoader? = null

    /** 已装载的功能 key，避免重复 hook；配置刷新后只补装缺的。 */
    private val loadedKeys = ConcurrentHashMap.newKeySet<String>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != WECHAT_PACKAGE) return

        val process = lpparam.processName ?: return
        if (process != WECHAT_PACKAGE) {
            Logger.i(TAG, "跳过进程：$process")
            return
        }

        Logger.i(TAG, "已注入进程：$process（模块 v${BuildConfig.VERSION_NAME}）")

        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.args[0] as? Context ?: return
                        if (!installed.compareAndSet(false, true)) return
                        // WeKit 用的是 application.baseContext.classLoader 而不是 lpparam.classLoader：
                        // 微信有 Tinker 热修复，实际承载业务类的可能是后续替换掉的 ClassLoader。
                        val realCl = runCatching {
                            (param.thisObject as? Context)?.let { baseContextClassLoader(it) }
                        }.getOrNull() ?: lpparam.classLoader
                        Thread({ installAll(lpparam, ctx, realCl) }, "Wxcj-init").start()
                    }
                },
            )
        }.onFailure { Logger.e(TAG, "hook Application.attach 失败", it) }

        Thread({
            runCatching { Thread.sleep(FALLBACK_DELAY_MS) }
            if (!installed.compareAndSet(false, true)) return@Thread
            Logger.w(TAG, "走兜底路径初始化")
            val app = currentApplication()
            val cl = app?.let { runCatching { baseContextClassLoader(it) }.getOrNull() }
                ?: lpparam.classLoader
            installAll(lpparam, app, cl)
        }, "Wxcj-fallback").start()
    }

    private fun installAll(
        lpparam: XC_LoadPackage.LoadPackageParam,
        context: Context?,
        realClassLoader: ClassLoader,
    ) {
        Notifier.attach(context, lpparam)
        // 系统层 hook（虚拟定位兜底）用的是框架给的这个 ClassLoader，先存下来
        hostClassLoader = lpparam.classLoader

        context?.let { ctx ->
            runCatching {
                val info = ctx.packageManager.getPackageInfo(WECHAT_PACKAGE, 0)
                Logger.i(TAG, "宿主版本：${info.versionName}（${info.versionCode}）")
            }.onFailure { Logger.w(TAG, "读取宿主版本失败：${it.message}") }
        }

        val sameLoader = realClassLoader === lpparam.classLoader
        Logger.i(TAG, "ClassLoader：lpparam=${lpparam.classLoader} / baseContext=$realClassLoader / 相同=$sameLoader")

        // ---- 配置 ----
        val prefs = Prefs.remote(context)
        Logger.i(TAG, "配置来源：${prefs.source}")
        // 先取出两个「控制提示音量」的开关：后面的诊断提示必须服从它们，
        // 否则无论开关怎么设都会照常刷屏。
        val noticeEarly = prefs.getBoolean(Prefs.KEY_STARTUP_NOTICE, Prefs.DEFAULT_STARTUP_NOTICE)
        val diagEarly = prefs.getBoolean(Prefs.KEY_DIAGNOSTIC, Prefs.DEFAULT_DIAGNOSTIC)

        val configOk = prefs.source != Prefs.SOURCE_DEFAULT
        // 来源只在「开了提示或诊断」时才弹；两者都关时只保留失败告警
        if (noticeEarly || diagEarly) Notifier.notify("自检⓪：配置来源 ${prefs.source}")
        if (!configOk) {
            Notifier.notify("⚠ 未读到配置，先用默认值启动，后台继续重试（约 20 秒内会补装）")
        } else if (prefs.source == Prefs.SOURCE_MIRROR && (noticeEarly || diagEarly)) {
            Notifier.notify("⚠ 用的是宿主侧镜像（可能不是最新），模块进程没起来 → 把 MM 加入自启动/电池白名单")
        }

        // 读到真实配置就写镜像，供下次冷启动兜底
        Prefs.mirrorSave(context, prefs)

        // ---- 监听配置变更：设置页改开关后即时热装载，不必重启微信 ----
        val appCtx = context?.applicationContext
        if (appCtx != null) {
            runCatching {
                val filter = IntentFilter(Prefs.ACTION_PREFS_CHANGED)
                // RECEIVER_EXPORTED：广播来自另一个 UID（模块 App），非 exported 收不到
                if (Build.VERSION.SDK_INT >= 33) {
                    appCtx.registerReceiver(prefsReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    appCtx.registerReceiver(prefsReceiver, filter)
                }
                Logger.i(TAG, "已注册配置变更监听")
            }.onFailure { Logger.w(TAG, "注册配置变更监听失败：${it.message}") }
        }

        // ---- 自检：不依赖 DexKit，先确认 hook 链路本身是通的 ----
        val selfTestOk = if (diagEarly) {
            runCatching { SelfTest.install(lpparam.classLoader) }.getOrDefault(false)
        } else {
            false
        }
        val diag = diagEarly
        if (diag) {
            Notifier.notify(
                if (selfTestOk) "自检①：hook 链路正常（LauncherUI.onResume 已挂上）"
                else "自检①：hook 链路异常 ${SelfTest.installError ?: "未知"}"
            )
        }

        syncSettings(prefs)

        // 内容快照：只读，失败静默降级（提示里只显示名字，不影响拦截）
        val snapshotOk = runCatching { MsgSnapshots.install(context, realClassLoader) }.getOrDefault(false)
        Logger.i(TAG, "消息内容快照：${if (snapshotOk) "已启用" else "不可用（提示将只显示名字）"}")

        // 探针纯粹是诊断用的（额外挂 3 个 onResume hook），关闭诊断时完全不装
        val probeErrors = if (diag) {
            runCatching { Probe.install(lpparam, realClassLoader) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (diag) {
            val desc = if (probeErrors.isEmpty()) "三组全部成功" else probeErrors.joinToString("; ")
            Notifier.notify("探针已装：$desc")
        }

        val notice = noticeEarly
        val rescanToken = prefs.getInt(Prefs.KEY_RESCAN_TOKEN, 0)

        val recall = prefs.getBoolean(Prefs.KEY_ANTI_RECALL, Prefs.DEFAULT_ANTI_RECALL)
        val typing = prefs.getBoolean(Prefs.KEY_DISABLE_TYPING, Prefs.DEFAULT_DISABLE_TYPING)
        val pat = prefs.getBoolean(Prefs.KEY_DISABLE_PAT, Prefs.DEFAULT_DISABLE_PAT)
        val fake = prefs.getBoolean(Prefs.KEY_FAKE_LOCATION, Prefs.DEFAULT_FAKE_LOCATION)
        val enabledCount = listOf(recall, typing, pat, fake).count { it }
        Logger.i(
            TAG,
            "开关：防撤回=$recall，禁止输入状态=$typing，禁用拍一拍=$pat，虚拟定位=$fake"
        )

        if (notice) {
            Notifier.notify(
                if (enabledCount > 0) "MM 已注入 · $enabledCount 个功能已启用"
                else "MM 已注入 · 未开启任何功能"
            )
        }

        // ---- 后台刷新配置（来源不是 Provider 时才需要）----
        // 先把 context 收成非空局部量再进 lambda，避免在 lambda 里对可空参数做智能转换
        val refreshCtx = context
        if (prefs.source != Prefs.SOURCE_PROVIDER && refreshCtx != null) {
            Thread({
                val fresh = Prefs.refresh(refreshCtx)
                if (fresh == null) {
                    Logger.w(TAG, "后台重试未读到配置，本次沿用 ${prefs.source}")
                    return@Thread
                }
                Prefs.mirrorSave(refreshCtx, fresh)
                val freshDiag = syncSettings(fresh)
                Notifier.notify("配置已刷新（${fresh.source}）")
                // 热装载：只补装还没挂上的
                val targets = resolvedTargets
                if (targets != null) {
                    val ok = ArrayList<String>()
                    val bad = ArrayList<String>()
                    applyFeatures(fresh, targets, freshDiag, ok, bad)
                    if (ok.isNotEmpty()) Notifier.notify("已补装：${ok.joinToString("、")}")
                    if (bad.isNotEmpty()) Notifier.notify("仍未生效：${bad.joinToString("、")}")
                }
            }, "Wxcj-refresh").start()
        }

        if (!recall && !typing && !pat && !fake) {
            Logger.i(TAG, "所有功能都关闭，跳过解析")
            return
        }

        val result = DexTargets.resolve(lpparam, context, rescanToken, realClassLoader)
        val targets = result.targets
        resolvedTargets = targets
        Logger.i(TAG, "解析完成，共 ${targets.size} 个目标")

        if (diag) {
            Notifier.notify(
                if (result.scanned) {
                    "自检②：已扫 dex（${result.elapsedMs / 1000}秒）· 命中 ${targets.size}/${DexTargets.TOTAL}"
                } else {
                    "自检②：命中缓存 · ${targets.size}/${DexTargets.TOTAL}（未重新扫描）"
                }
            )
            if (targets.isEmpty()) {
                Notifier.notify("自检③：一个目标都没定位到 → 问题在 DexKit（so 或扫描源）")
            } else if (result.missing.isNotEmpty()) {
                val names = result.missing.map(DexTargets::labelOf).distinct().joinToString("、")
                Notifier.notify("自检③：未定位到 → $names")
            } else {
                Notifier.notify("自检③：全部目标就绪，开始装 hook")
            }
        }

        if (notice && result.scanned) {
            val seconds = result.elapsedMs / 1000
            Notifier.notify(
                if (result.missing.isEmpty()) {
                    "MM 解析完成（${seconds}秒）· ${targets.size} 个目标全部就绪"
                } else {
                    val names = result.missing.map(DexTargets::labelOf).distinct().joinToString("、")
                    "MM 解析完成（${seconds}秒）· 成功 ${targets.size}/${DexTargets.TOTAL}，未定位：$names"
                }
            )
        }

        val okLabels = ArrayList<String>()
        val badLabels = ArrayList<String>()
        applyFeatures(prefs, targets, diag, okLabels, badLabels)

        if (notice || diag) {
            Notifier.notify(
                buildString {
                    append("MM 已装载 ")
                    append(okLabels.size)
                    append(" 项")
                    if (okLabels.isNotEmpty()) append("：").append(okLabels.joinToString("、"))
                    if (badLabels.isNotEmpty()) append("；未生效：").append(badLabels.joinToString("、"))
                }
            )
        }

        // 自检回调确认：只有真正回到微信主界面才会触发，延迟再看一次
        if (diag && selfTestOk) {
            Thread({
                // 关键：先等微信真的进前台，否则所有计数都是 0，会误判成 hook 失效。
                // 之前是启动后固定 sleep 20 秒，微信在后台时数据全 0，完全没参考价值。
                val gotForeground = Notifier.waitForForeground(60_000)
                runCatching { Thread.sleep(20_000) }

                if (!gotForeground) {
                    Notifier.notify("自检④：60 秒内微信未进入前台，以下计数全部无效，请打开微信界面后重开诊断")
                }

                val n = SelfTest.callbackCount
                Notifier.notify(
                    if (n > 0) "自检④：hook 回调已触发 $n 次 → 注入完全正常"
                    else "自检④：hook 回调 0 次${if (gotForeground) " → 已在前台仍为 0，说明 hook 未生效" else "（微信在后台，无参考价值）"}"
                )
                if (recall) Notifier.notify(
                    "防撤回 sysmsg：调用 ${AntiRecall.triggeredCount} 次，拦截 ${AntiRecall.blockedCount} 条"
                )
                if (recall) Notifier.notify(
                    "防撤回 doRevokeMsg：调用 ${AntiRecall.revokeTriggeredCount} 次，拦截 ${AntiRecall.revokeBlockedCount} 条"
                )
                // 内容显示不出来时，这一条能直接看出断在哪一步
                if (recall) Notifier.notify("内容快照：${MsgSnapshots.diagnostics()}")
                if (typing) Notifier.notify(
                    "禁止输入状态：doScene 拦截 ${DisableTypingStatus.blockedCount} 次"
                )
                if (fake) Notifier.notify(FakeLocation.report())
                Notifier.notify(Probe.report() + "｜ClassLoader相同=$sameLoader")
                Notifier.notify(Notifier.flushDiagnostics())
            }, "Wxcj-selftest").start()
        }
    }

    /**
     * 把配置同步到各功能的静态开关上。
     *
     * **每次拿到新配置都必须调用一次**——包括初始读取、后台重试成功、
     * 以及收到设置页变更广播。之前只在初始读取时同步，导致后台重试读到真实配置后
     * 「撤回提示」这类开关仍是旧值，表现为「明明开了却没效果」。
     *
     * @return 解析出的诊断开关值，供调用方决定是否弹诊断提示
     */
    private fun syncSettings(prefs: Prefs.Snapshot): Boolean {
        val diag = prefs.getBoolean(Prefs.KEY_DIAGNOSTIC, Prefs.DEFAULT_DIAGNOSTIC)
        AntiRecall.verbose = diag
        DisableTypingStatus.verbose = diag

        AntiRecall.showNotice = prefs.getBoolean(Prefs.KEY_RECALL_NOTICE, Prefs.DEFAULT_RECALL_NOTICE)
        AntiRecall.noticeTemplate =
            prefs.getString(Prefs.KEY_RECALL_NOTICE_TEXT, Prefs.DEFAULT_RECALL_NOTICE_TEXT).let {
                if (it.isBlank()) Prefs.DEFAULT_RECALL_NOTICE_TEXT else it
            }
        AntiRecall.confirmNotice = prefs.getBoolean(Prefs.KEY_RECALL_CONFIRM, Prefs.DEFAULT_RECALL_CONFIRM)
        AntiRecall.recallSelf = prefs.getBoolean(Prefs.KEY_RECALL_SELF, Prefs.DEFAULT_RECALL_SELF)
        Logger.i(TAG, "防撤回带名提示=${AntiRecall.showNotice}，模板「${AntiRecall.noticeTemplate}」，诊断=$diag")

        FakeLocation.verbose = diag
        // 坐标与开关都在 hook 内部读取，因此**关闭也能即时生效** ——
        // 这是虚拟定位与其他功能的区别：其他功能靠「装不装 hook」，关掉就得重启。
        FakeLocation.enabled =
            prefs.getBoolean(Prefs.KEY_FAKE_LOCATION, Prefs.DEFAULT_FAKE_LOCATION)
        FakeLocation.latitude =
            prefs.getString(Prefs.KEY_FAKE_LAT, Prefs.DEFAULT_FAKE_LAT).toDoubleOrNull()
                ?: Prefs.DEFAULT_FAKE_LAT.toDouble()
        FakeLocation.longitude =
            prefs.getString(Prefs.KEY_FAKE_LNG, Prefs.DEFAULT_FAKE_LNG).toDoubleOrNull()
                ?: Prefs.DEFAULT_FAKE_LNG.toDouble()
        FakeLocation.jitterMeters = prefs.getInt(Prefs.KEY_FAKE_JITTER, Prefs.DEFAULT_FAKE_JITTER)
        Logger.i(
            TAG,
            "虚拟定位坐标=${FakeLocation.latitude},${FakeLocation.longitude}，" +
                "抖动=${FakeLocation.jitterMeters}米"
        )
        return diag
    }

    /** 按当前配置装载功能；已在 [loadedKeys] 里的跳过，因此可重复调用（配置刷新后补装）。 */
    private fun applyFeatures(
        prefs: Prefs.Snapshot,
        targets: Map<String, List<Method>>,
        verbose: Boolean,
        okLabels: MutableList<String>,
        badLabels: MutableList<String>,
    ) {
        val recall = prefs.getBoolean(Prefs.KEY_ANTI_RECALL, Prefs.DEFAULT_ANTI_RECALL)
        val typing = prefs.getBoolean(Prefs.KEY_DISABLE_TYPING, Prefs.DEFAULT_DISABLE_TYPING)
        val pat = prefs.getBoolean(Prefs.KEY_DISABLE_PAT, Prefs.DEFAULT_DISABLE_PAT)
        val fake = prefs.getBoolean(Prefs.KEY_FAKE_LOCATION, Prefs.DEFAULT_FAKE_LOCATION)

        installOne(
            "防撤回", recall, "recall", listOf(DexTargets.K_XML_PARSER),
            targets, okLabels, badLabels, verbose,
        ) {
            AntiRecall.install(it)
        }
        // 第二条独立路径：直接取消微信的撤回处理方法。与 sysmsg 路径互为备份。
        installOne(
            "防撤回(doRevokeMsg)", recall, "recallRevoke", listOf(DexTargets.K_DO_REVOKE_MSG),
            targets, okLabels, badLabels, verbose,
        ) {
            AntiRecall.installDoRevoke(it)
        }
        installOne(
            "禁止上传正在输入", typing, "typing", listOf(DexTargets.K_TYPING_DO_SCENE),
            targets, okLabels, badLabels, verbose,
        ) {
            DisableTypingStatus.install(it)
        }
        installOne(
            "禁用拍一拍", pat, "pat", listOf(DexTargets.K_PAT_DOUBLE_CLICK),
            targets, okLabels, badLabels, verbose,
        ) {
            DisablePat.install(it)
        }

        // 虚拟定位：三条回调路径的候选合并起来一起装（命中一条就够用，多装无害）。
        // 它另有不依赖 DexKit 的系统层兜底，见 installFakeLocationFallback。
        installOne(
            "虚拟定位", fake, "fakeLocation", DexTargets.LOCATION_KEYS,
            targets, okLabels, badLabels, verbose,
        ) {
            FakeLocation.install(it)
        }

        hostClassLoader?.let { installFakeLocationFallback(fake, it) }
    }

    /**
     * 虚拟定位的系统层兜底：不依赖 DexKit（直接 hook Android 的 LocationManager），
     * 所以放在这里单独装，没定位到微信回调时它仍可能生效。
     */
    private fun installFakeLocationFallback(enabled: Boolean, classLoader: ClassLoader) {
        if (!enabled) return
        if (!loadedKeys.add("fakeLocationSys")) return
        val ok = runCatching { FakeLocation.installSystemFallback(classLoader) }.getOrDefault(false)
        if (ok) {
            Logger.i(TAG, "虚拟定位：系统层兜底已挂载")
            return
        }
        // 兜底挂不上不影响主路径（微信自己的定位回调），所以不进 badLabels，
        // 免得提示里出现「未生效：虚拟定位」这种误导。
        loadedKeys.remove("fakeLocationSys")
        Logger.w(TAG, "虚拟定位：系统层兜底未挂载（不影响微信回调那条路径）")
    }

    private fun installOne(
        label: String,
        enabled: Boolean,
        stateKey: String,
        dexKeys: List<String>,
        targets: Map<String, List<Method>>,
        okLabels: MutableList<String>,
        badLabels: MutableList<String>,
        verbose: Boolean,
        install: (List<Method>) -> Int,
    ) {
        if (!enabled) return
        if (!loadedKeys.add(stateKey)) return   // 已装过，跳过
        val candidates = dexKeys.flatMap { targets[it].orEmpty() }.distinct()
        if (candidates.isEmpty()) {
            loadedKeys.remove(stateKey)          // 本次没装成，允许以后重试
            Logger.w(TAG, "$label 已开启但未定位到目标（${dexKeys.joinToString()}）")
            badLabels += label
            return
        }
        val n = runCatching { install(candidates) }
            .onFailure { Logger.e(TAG, "$label 安装失败", it) }
            .getOrDefault(0)
        if (n > 0) {
            okLabels += "$label(${n}处)"
            val names = candidates.joinToString { it.declaringClass.name }
            Logger.i(TAG, "$label 已挂载 $n 处：$names")
            if (verbose) Notifier.notify("$label → $names")
        } else {
            loadedKeys.remove(stateKey)
            badLabels += label
        }
    }

    /**
     * 收到「配置变了」广播后重读配置并补装还没挂上的功能。
     *
     * 注意：已经开启的功能**不会被关闭**——Xposed 的 hook 无法安全卸载。
     * 所以关掉某个开关后仍需重启微信；开启则可以即时生效。
     */
    private val prefsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Logger.i(TAG, "收到配置变更广播，重新读取")
            val pending = goAsync()
            Thread {
                try {
                    val fresh = Prefs.remote(context.applicationContext)
                    Prefs.mirrorSave(context.applicationContext, fresh)
                    Notifier.notify("配置已更新（${fresh.source}）")
                    val targets = resolvedTargets
                    if (targets != null) {
                        val ok = ArrayList<String>()
                        val bad = ArrayList<String>()
                        val verbose = syncSettings(fresh)
                        applyFeatures(fresh, targets, verbose, ok, bad)
                        if (ok.isNotEmpty()) Notifier.notify("已生效：${ok.joinToString("、")}")
                    }
                } finally {
                    runCatching { pending.finish() }
                }
            }.start()
        }
    }

    /** 通过 ActivityThread.currentApplication() 取宿主 Context。 */
    private fun currentApplication(): Context? = runCatching {
        val atClass = XposedHelpers.findClass("android.app.ActivityThread", null)
        val at = XposedHelpers.callStaticMethod(atClass, "currentActivityThread")
        XposedHelpers.callMethod(at, "getApplication") as? Context
    }.onFailure { Logger.w(TAG, "获取 ActivityThread 失败：${it.message}") }.getOrNull()

    /** 取 Application 最底层 baseContext 的 ClassLoader（WeKit 用的是同一个）。 */
    private fun baseContextClassLoader(start: Context): ClassLoader {
        var ctx: Context = start
        var depth = 0
        while (ctx is android.content.ContextWrapper && depth < 10) {
            val base = ctx.baseContext
            if (base === ctx) break
            ctx = base
            depth++
        }
        return ctx.classLoader ?: start.javaClass.classLoader
    }

    companion object {
        private const val TAG = "Entry"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val FALLBACK_DELAY_MS = 8_000L

        /** 解析结果，供配置刷新后的补装使用 */
        @Volatile
        private var resolvedTargets: Map<String, List<Method>>? = null
    }
}
