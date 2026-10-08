package dev.mm.wxcj

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.app.ActivityManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 模块自己的设置页。开关写进 SharedPreferences，微信进程通过 PrefsProvider 读取。
 *
 * 改开关后会发一条显式广播通知微信进程重读并热装载，因此**改完立刻生效，
 * 不必再重启微信**（前提：微信正在运行）。
 */
class MainActivity : Activity() {

    /** 模板输入框，供 onPause 兜底保存用。 */
    private var templateEdit: EditText? = null

    /** 抖动输入框，同样要在 onPause 兜底保存 */
    private var jitterEdit: EditText? = null
    /** 当前坐标的只读展示 */
    private var fakeCoordText: TextView? = null
    private val fakeCommits = ArrayList<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindSwitch(R.id.switchAntiRecall, Prefs.KEY_ANTI_RECALL, Prefs.DEFAULT_ANTI_RECALL)
        bindSwitch(R.id.switchRecallNotice, Prefs.KEY_RECALL_NOTICE, Prefs.DEFAULT_RECALL_NOTICE)
        bindSwitch(R.id.switchRecallConfirm, Prefs.KEY_RECALL_CONFIRM, Prefs.DEFAULT_RECALL_CONFIRM)
        bindSwitch(R.id.switchRecallSelf, Prefs.KEY_RECALL_SELF, Prefs.DEFAULT_RECALL_SELF)
        bindTemplate()
        bindSwitch(R.id.switchDisableTyping, Prefs.KEY_DISABLE_TYPING, Prefs.DEFAULT_DISABLE_TYPING)
        bindSwitch(R.id.switchDisablePat, Prefs.KEY_DISABLE_PAT, Prefs.DEFAULT_DISABLE_PAT)
        bindFakeLocation()
        bindSwitch(R.id.switchStartupNotice, Prefs.KEY_STARTUP_NOTICE, Prefs.DEFAULT_STARTUP_NOTICE)
        bindSwitch(R.id.switchDiagnostic, Prefs.KEY_DIAGNOSTIC, Prefs.DEFAULT_DIAGNOSTIC)
        bindRescanButton()
        bindKillButton()
        bindRootEntry()
    }

    /** 从微信里选完点再切回设置页时，这里要能看到新坐标。 */
    override fun onResume() {
        super.onResume()
        runCatching { refreshFakeCoord() }
    }

    /**
     * 兜底保存模板。
     *
     * 关闭 Activity 不保证触发 View 的焦点变化，所以必须在这里再存一次，
     * 否则「改完直接退出」的改动会全部丢失。
     */
    override fun onPause() {
        runCatching { commitTemplate() }
        // 坐标/抖动同模板：关闭 Activity 不保证触发焦点变化，这里再兜一次
        fakeCommits.forEach { runCatching(it) }
        runCatching { refreshFakeCoord() }
        super.onPause()
    }

    /**
     * 每点一次就把重扫令 +1；宿主进程发现 token 变了就会丢弃 dex 缓存重新扫描。
     *
     * 数字只是个内部版本号，对用户没有意义，所以提示里不暴露它。
     */
    private fun bindRescanButton() {
        findViewById<Button>(R.id.btnRescan).setOnClickListener {
            val next = Prefs.local(this).getInt(Prefs.KEY_RESCAN_TOKEN, 0) + 1
            Prefs.local(this).edit().putInt(Prefs.KEY_RESCAN_TOKEN, next).apply()
            Toast.makeText(this, getString(R.string.rescan_done), Toast.LENGTH_LONG).show()
        }
    }

    /** 一键结束微信后台：等价于「从最近任务划掉」，下次打开是真正的冷启动。 */
    private fun bindKillButton() {
        val result = findViewById<TextView>(R.id.txtKillResult)
        val btn = findViewById<Button>(R.id.btnKillWechat)
        btn.setOnClickListener {
            Toast.makeText(this, getString(R.string.kill_running), Toast.LENGTH_SHORT).show()
            btn.isEnabled = false
            Thread {
                val (ok, detail) = forceStopWeChat()
                runOnUiThread {
                    btn.isEnabled = true
                    Toast.makeText(
                        this,
                        getString(if (ok) R.string.kill_ok else R.string.kill_fail),
                        Toast.LENGTH_LONG,
                    ).show()
                    // 失败时把 su 的原始输出留在界面上，方便判断是没授权还是命令不支持
                    result.text = detail
                    result.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
                }
            }.start()
        }
    }

    /**
     * root 入口：检测按钮 + 手动固定下拉。
     *
     * 为什么要能手动固定：自动探测每换一次（授权撤销、换 root 方案）就要把候选
     * 挨个试一遍，每次都要 fork 一个进程等超时。固定之后直接命中，零探测开销。
     * 检测按钮只跑一次探测，把结果摆出来给用户看，不执行任何命令。
     */
    private fun bindRootEntry() {
        val result = findViewById<TextView>(R.id.txtKillResult)
        val names = ArrayList<String>().apply {
            add(getString(R.string.root_entry_auto))
            addAll(SU_INVOKERS.map { it.name })
        }
        val values = ArrayList<String>().apply {
            add("")                                  // 自动
            addAll(SU_INVOKERS.map { it.name })
        }

        val spinner = findViewById<Spinner>(R.id.spinnerRootEntry)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val prefs = Prefs.local(this)
        val saved = prefs.getString(KEY_SU_LOCK, "").orEmpty()
        val savedIndex = values.indexOf(saved)
        spinner.setSelection(if (savedIndex >= 0) savedIndex else 0)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = values.getOrNull(position) ?: return
                prefs.edit().putString(KEY_SU_LOCK, value).commit()
                if (value.isEmpty()) {
                    // 切回自动：清掉缓存的探测结果，下次重新探测
                    prefs.edit().remove(KEY_SU_INVOKER).apply()
                    result.text = getString(R.string.root_entry_auto)
                } else {
                    result.text = getString(R.string.root_entry_locked, value)
                }
                result.visibility = View.VISIBLE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // 检测：只做探测，不执行任何 root 命令
        findViewById<Button>(R.id.btnDetectRoot).setOnClickListener {
            btnDetectEnabled(false, it as Button)
            Thread {
                val info = ArrayList<String>()
                val invoker = resolveInvoker(info, forceProbe = true)
                val env = detectRootEnv()
                val uidOk = if (invoker != null) {
                    val (code, out) = runCommand(invoker.buildCmd("id -u"), SU_PROBE_TIMEOUT_MS)
                    if (code == 0) out.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty() else "失败(code=$code)"
                } else {
                    "不可用"
                }
                runOnUiThread {
                    btnDetectEnabled(true, findViewById(R.id.btnDetectRoot))
                    val text = buildString {
                        append(getString(R.string.root_detect_result, invoker?.name ?: "未找到", uidOk))
                        append("\nroot 方案：").append(env ?: "未识别")
                        if (info.isNotEmpty()) append("\n").append(info.joinToString("\n"))
                    }
                    result.text = text
                    result.visibility = View.VISIBLE
                    Logger.i("MainActivity", "root 检测：$text")
                }
            }.start()
        }
    }

    private fun btnDetectEnabled(enabled: Boolean, btn: Button) {
        btn.isEnabled = enabled
        btn.text = if (enabled) getString(R.string.root_detect) else getString(R.string.kill_running)
    }

    /**
     * 结束微信后台进程。
     *
     * ## 两条路，先无 root 再有 root
     *
     * 1. `ActivityManager.killBackgroundProcesses()`：系统 API，
     *    普通权限（`KILL_BACKGROUND_PROCESSES` 是 normal 级，声明即可），
     *    由 AMS 正常停止，不会误伤任何别的进程。缺点是**杀不掉前台进程**。
     * 2. root 的 `am force-stop`：能杀前台进程，同样走 AMS。
     *
     * ## 为什么不再用 killall / pkill
     *
     * 这是之前两个怪现象（MM 自己退出到桌面、WIFI 短暂断开重连）的直接来源：
     *
     *  - **`pkill -f com.tencent.mm` 会杀掉执行它的 shell 自己**。
     *    `-f` 匹配完整命令行，而 `pkill` 进程和它的父 shell 的命令行里
     *    正好都含 `com.tencent.mm`。su 会话被信号打死，命令返回码和管道都不可靠，
     *    root 方案（KernelSU/APatch/Magisk）在会话异常终止时行为各异，
     *    有时还会牵连同一会话里的其他进程。
     *  - **`killall` / `pkill` 绕过 AMS 直接发信号**。微信的 push / core service
     *    都是 `START_STICKY`，被信号杀掉后系统会立刻拉起，AMS 与真实进程状态不一致。
     *    这种「异常死亡」正是厂商 ROM 的网络/内存管理重新评估网络的典型诱因
     *    （表现为 WIFI 短暂断开再重连）。
     *
     * 现在只保留 AMS 认可的两条路，不碰信号级 kill。
     */
    private fun forceStopWeChat(): Pair<Boolean, String> {
        val pkg = Prefs.WECHAT_PACKAGE
        val details = ArrayList<String>()

        // ---- 1) 无 root：系统 API，最安全 ----
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            val ok = runCatching { am.killBackgroundProcesses(pkg); true }
                .onFailure { details += "killBackgroundProcesses → ${it.javaClass.simpleName}: ${it.message}" }
                .getOrDefault(false)
            if (ok) details += "killBackgroundProcesses → 已调用"
        }

        // ---- 2) 有 root：am force-stop，能连前台进程一起停 ----
        //
        // 先**探测可用的 su**，再执行。原因：KernelSU / APatch / Magisk 各自把 su
        // 放在不同的绝对路径上，且 KernelSU 未必把 su 注入到普通 app 的 PATH ——
        // 直接写 "su" 在 KernelSU 上很可能得到 127（command not found），
        // 于是白试一轮还被当成「没授权」。
        val invoker = resolveInvoker(details)
        details += "root 入口：${invoker?.name ?: "未找到"}"

        var forceStopped = false
        if (invoker != null) {
            // `am` 先按 PATH 找，失败再用绝对路径（root shell 的 PATH 有时不含 /system/bin）。
            // 最后退到 `cmd activity`，老版本 Android 上 am 可能不在 PATH 里。
            //
            // 每种入口还有两种调用形式（`-c "整条命令"` 与 `0 am force-stop <pkg>`），
            // 由 [SuInvoker] 自行决定支持哪些 —— 探测时已经验证过能拿 uid=0，
            // 这里只需要在失败时换形式重试。
            val attempts = ArrayList<Array<String>>()
            // 先试 `-c "整条命令"` 的三种写法
            for (command in listOf(
                "am force-stop $pkg",
                "/system/bin/am force-stop $pkg",
                "cmd activity force-stop $pkg",
            )) {
                attempts += invoker.buildCmd(command)
            }
            // 再试直接传 argv 的写法（`su 0 am force-stop <pkg>`），不支持的入口返回 null
            invoker.buildArgv(arrayOf("0", "am", "force-stop", pkg))?.let { attempts += it }

            for (cmd in attempts) {
                val (code, output) = runCommand(cmd, timeoutMs = SU_TIMEOUT_MS)
                if (output.isNotEmpty()) details += "${cmd.joinToString(" ")} → $output"
                if (code == 0) {
                    forceStopped = true
                    details += "✓ ${cmd.joinToString(" ")}"
                    break
                }
                // 超时说明在等授权（用户没点 root 管理器的弹窗），
                // 别再往下试，否则会反复拉起授权请求。
                if (code == TIMEOUT_CODE) {
                    details += "${cmd.joinToString(" ")} → 超时（可能在等 root 授权）"
                    break
                }
            }
            if (!forceStopped) {
                details += "所有 root 命令均失败，请确认已在 root 管理器里授予本应用权限"
            }
        }

        return if (forceStopped) {
            true to "已强制停止微信（am force-stop）\n${details.takeLast(2).joinToString("\n")}"
        } else {
            false to details.take(8).joinToString("\n")
        }
    }

    /** 单次 su 调用的超时（毫秒）。KernelSU 首次授权弹窗若没人点，不能一直等。 */
    private val SU_TIMEOUT_MS = 6_000L

    /** 探测 su 时的超时：短一点，避免首次要试多个路径时等太久。 */
    private val SU_PROBE_TIMEOUT_MS = 3_000L

    /** 超时返回码（区别于命令自己返回的非 0 值） */
    private val TIMEOUT_CODE = -999

    /** 缓存已探测到的 su 路径，避免每次点按钮都全试一轮。 */
    private val KEY_SU_INVOKER = "su_invoker"

    /** 手动固定的 root 入口名；空 = 自动（用探测并缓存的结果） */
    private val KEY_SU_LOCK = "su_lock"

    /**
     * 一种「拿到 root shell 并执行命令」的方式。
     *
     * 为什么不能只存一个 su 路径：**不同 root 方案连调用形式都不一样**。
     * KernelSU 上可能根本没有 su 文件，得靠 `ksud` 自己扮演 su（见 [SU_INVOKERS]）。
     *
     * @param name 显示给用户看的名字（诊断用）
     * @param file 用于快速预筛的特征文件，null 表示走 PATH 无法预筛
     * @param buildCmd 把一条 shell 命令包成 argv（`-c` 形式）
     * @param buildArgv 直接传 argv 的形式（`su 0 am force-stop pkg`），不支持返回 null
     */
    private data class SuInvoker(
        val name: String,
        val file: String?,
        val buildCmd: (String) -> Array<String>,
        val buildArgv: (Array<String>) -> Array<String>? = { null },
    )

    /** ksud 可能的安装位置（`/data/adb/ksud` 是旧版路径） */
    private val KSUD_PATHS = listOf("/data/adb/ksu/bin/ksud", "/data/adb/ksud")

    /** 给 sh -c 里的命令加单引号，避免空格/特殊字符被拆开 */
    private fun shQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /**
     * 所有已知的 root 入口，按当前主流环境的实际命中率排序。
     *
     * ## KernelSU 到底有没有 su（这是本版修正的核心）
     *
     * KernelSU **不提供 su 二进制文件**，这是官方明确的设计。网上常见的
     * `su: not found` 报错就来自这里。它的工作方式是：
     *
     * 1. **sucompat 兼容层**（内核态）：内核拦截 `execve` / `faccessat` / `newfstatat`，
     *    对**白名单内的 UID** 把 `/system/bin/su` 这个路径重定向到 `/data/adb/ksud`。
     *    所以文件管理器里看不到 su，但执行 `/system/bin/su -c "id -u"` 能拿到 uid=0。
     * 2. **ksud 自己扮演 su**（用户态）：ksud 被以 `argv[0] == "su"` 调用时，
     *    会走 `root_shell()`，支持 `-c` / `-l` / `-p` / `-s` / `-M` / `-g` / `-G` 等参数。
     *    KernelSU 源码里那个极简 `su.c` 只是演示，并**不在标准安装包里**。
     *
     * 因此在 KernelSU 上有两条可用路径，本模块**两条都试**：
     *  - `/system/bin/su`（走 sucompat 重定向，需要已授权）
     *  - `ksud` + `exec -a su`（不依赖授权状态，只依赖 ksud 本身可用）
     *
     * 后者用 `sh -c "exec -a su <ksud> -c '命令'"` 实现：`exec -a` 把 argv[0] 改成
     * `su`，ksud 就会切到 root_shell 分支。mksh（Android 默认 shell）支持 `-a`。
     *
     * ## 顺序
     *
     *  1. `/system/bin/su` —— KernelSU 官方入口（sucompat），也是 Magisk 常规位置
     *  2. `su` —— PATH（Magisk 注入；部分 KernelSU 也会注入）
     *  3. `ksud` 扮演 su —— **KernelSU 兜底**，su 文件缺失时靠它
     *  4. `/debug_ramdisk/su` —— 早期 KernelSU / Magisk 的 tmpfs 挂载点
     *  5. `/data/adb/ap/bin/su` —— APatch
     *  6. `/data/adb/magisk/su` —— 新版 Magisk
     *  7. `/data/adb/ksu/bin/su` —— 少数 KernelSU 打包确实放了真实 su
     *  8. 其余为历史路径
     */
    private val SU_INVOKERS: List<SuInvoker> = buildList {
        // 1) /system/bin/su —— KernelSU（sucompat 重定向）/ Magisk 常规位置
        add(
            SuInvoker(
                name = "/system/bin/su",
                file = "/system/bin/su",
                buildCmd = { cmd -> arrayOf("/system/bin/su", "-c", cmd) },
                buildArgv = { argv -> arrayOf("/system/bin/su", "0") + argv },
            )
        )

        // 2) su —— 走 PATH
        add(
            SuInvoker(
                name = "su (PATH)",
                file = null,
                buildCmd = { cmd -> arrayOf("su", "-c", cmd) },
                buildArgv = { argv -> arrayOf("su", "0") + argv },
            )
        )

        // 3) ksud 扮演 su —— KernelSU 在没有 su 文件时的兜底
        for (ksud in KSUD_PATHS) {
            add(
                SuInvoker(
                    name = "ksud 扮演 su ($ksud)",
                    file = ksud,
                    buildCmd = { cmd ->
                        arrayOf("/system/bin/sh", "-c", "exec -a su $ksud -c ${shQuote(cmd)}")
                    },
                )
            )
        }

        // 4~8) 其余已知位置
        for (path in listOf(
            "/debug_ramdisk/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su",
            "/data/adb/ksu/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
        )) {
            add(
                SuInvoker(
                    name = path,
                    file = path,
                    buildCmd = { cmd -> arrayOf(path, "-c", cmd) },
                    buildArgv = { argv -> arrayOf(path, "0") + argv },
                )
            )
        }
    }

    /** 各 root 方案的特征文件，只用于在失败时给出准确的排查提示。 */
    private val ROOT_ENV_MARKERS = listOf(
        "KernelSU" to listOf("/data/adb/ksu/bin/ksud", "/data/adb/ksud"),
        "APatch" to listOf("/data/adb/ap/bin/su", "/data/adb/apd"),
        "Magisk" to listOf("/data/adb/magisk/su", "/data/adb/magisk/magiskinit"),
    )

    /**
     * 本机用的是哪套 root 方案（按特征文件判断，识别不出返回 null）。
     *
     * 只用于失败提示：同样是「拿不到 root」，KernelSU 上通常是**没在管理器里
     * 给本应用授权**（sucompat 只服务白名单 UID），Magisk 上多半是**su 被隐藏**，
     * 排查方向完全不同。
     */
    private fun detectRootEnv(): String? {
        for ((name, markers) in ROOT_ENV_MARKERS) {
            if (markers.any { File(it).exists() }) return name
        }
        return null
    }

    /**
     * 找出一个真正能用的 root 入口。
     *
     * 判定标准是**实际执行 `id -u` 拿到 uid=0**，而不是文件是否存在：
     * KernelSU 的 app profile 可能把本应用设成拒绝（此时 su「存在」但调用被拒），
     * 也可能文件在但没有执行权限，或者压根没有这个文件（sucompat 虚拟路径）。
     *
     * 先用 `File.exists()` 跳过明显不存在的特征文件，避免挨个干等超时；
     * 走 PATH 的（`su`）没法预判，直接试。
     *
     * 命中后缓存名字；下次失效（换 root 方案 / 授权被撤销）自动重新探测。
     */
    private fun resolveInvoker(details: MutableList<String>, forceProbe: Boolean = false): SuInvoker? {
        val prefs = Prefs.local(this)

        // ---- 手动固定优先：跳过全部探测，零开销 ----
        // forceProbe=true 时忽略固定（「检测」按钮用，要真实探一遍给用户看）
        val locked = if (forceProbe) "" else prefs.getString(KEY_SU_LOCK, null).orEmpty()
        if (locked.isNotEmpty()) {
            val invoker = SU_INVOKERS.firstOrNull { it.name == locked }
            if (invoker != null) {
                details += "root 入口（已固定，跳过探测）：${invoker.name}"
                Logger.i("MainActivity", "root 入口：已固定 ${invoker.name}")
                return invoker
            }
        }

        val cachedName = prefs.getString(KEY_SU_INVOKER, null)
        if (cachedName != null) {
            val cached = SU_INVOKERS.firstOrNull { it.name == cachedName }
            if (cached != null && isRootShell(cached)) return cached
            // 缓存失效（换 root 方案 / 授权被撤销），清掉重新探测
            prefs.edit().remove(KEY_SU_INVOKER).apply()
        }

        var tried = 0
        for (invoker in SU_INVOKERS) {
            if (invoker.file != null && !File(invoker.file).exists()) continue
            tried++
            if (isRootShell(invoker)) {
                prefs.edit().putString(KEY_SU_INVOKER, invoker.name).apply()
                Logger.i("MainActivity", "root 入口：探测命中 ${invoker.name}（root 方案=${detectRootEnv() ?: "未知"}）")
                return invoker
            }
        }

        details += when (detectRootEnv()) {
            "KernelSU" ->
                "已识别到 KernelSU，但没拿到 root。请打开 KernelSU 管理器 → 超级用户，" +
                    "给「MM」授予 root 权限；未授权时 sucompat 不会为 MM 的 UID 做重定向，" +
                    "ksud 兜底入口也会被拒绝"
            "APatch" -> "已识别到 APatch，但没拿到 root。请在 APatch 管理器里给「MM」授权"
            "Magisk" ->
                "已识别到 Magisk，但没拿到 root。请确认 MM 在 Magisk 超级用户列表里，且 su 未被隐藏"
            else ->
                "未识别到 root 方案（试了 $tried 个入口）。请确认设备已 root，" +
                    "并在 root 管理器里给「MM」授权"
        }
        return null
    }

    /** 这个入口是不是真的能拿到 root shell？ */
    private fun isRootShell(invoker: SuInvoker): Boolean {
        val (code, out) = runCommand(invoker.buildCmd("id -u"), SU_PROBE_TIMEOUT_MS)
        if (code != 0) return false
        // 必须真的是 uid=0。只看「输出里含不含 0」会误判：
        // 报错信息里也可能有数字，而未授权时命令可能正常退出但输出的是提示文案。
        val first = out.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return first == "0" || first.startsWith("uid=0")
    }

    /**
     * 执行一条外部命令并取回输出。
     *
     * 三个关键点：
     *  - **`redirectErrorStream(true)`**：把 stderr 合并进 stdout。
     *    之前是「先 readText() 读 stdout 到 EOF，再读 stderr」——子进程若往 stderr
     *    写满 64KB 管道缓冲区就会阻塞，而父进程还在等 stdout 的 EOF，双方互等即死锁。
     *  - **带超时的 `waitFor`**：su 可能在等 root 授权界面（用户没点），
     *    不带超时就会永久挂起这个后台线程。
     *  - **超时后 `destroyForcibly()`**：顺手清掉卡住的 su 会话。
     */
    private fun runCommand(cmd: Array<String>, timeoutMs: Long): Pair<Int, String> {
        val output = StringBuilder()
        return runCatching {
            val process = ProcessBuilder(cmd.toList())
                .redirectErrorStream(true)
                .start()

            // 单独线程读，避免输出量大时把管道堵死（虽然已合并流，仍需有人在读）
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { output.append(it).append('\n') }
                    }
                }
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                reader.join(500)
                TIMEOUT_CODE to output.toString().trim()
            } else {
                reader.join(1_000)
                process.exitValue() to output.toString().trim()
            }
        }.onFailure {
            output.append("${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(-1 to output.toString().trim())
    }

    /**
     * 提示模板。
     *
     * ## 之前存不住的原因
     *
     * 只在 `setOnFocusChangeListener` 里保存，而**关闭 Activity 并不保证会让
     * EditText 失焦** —— 尤其软键盘还开着时直接返回/上滑关闭，焦点变化根本不触发，
     * 于是改动永远没写进 SharedPreferences。
     *
     * 另外 `apply()` 是异步落盘，紧接着被杀进程也可能丢。
     *
     * ## 现在三层保险
     *
     *  1. `TextWatcher` 每改一个字符就 **commit()**（同步落盘）；
     *  2. `onPause()` 再兜底保存一次；
     *  3. 失焦/暂停时才发广播通知微信进程，避免每敲一个字就广播一次。
     */
    private fun bindTemplate() {
        templateEdit = findViewById(R.id.editRecallNotice)
        val edit = templateEdit ?: return
        val prefs = Prefs.local(this)

        // 先 setText 再挂监听，避免初始化那次被当成用户改动
        edit.setText(prefs.getString(Prefs.KEY_RECALL_NOTICE_TEXT, Prefs.DEFAULT_RECALL_NOTICE_TEXT))
        edit.setSelection(edit.text.length)

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // 边输入边保存；空白时不处理，交给 onPause 恢复默认
                val text = s?.toString() ?: return
                if (text.isBlank()) return
                saveTemplate(text, notify = false)
            }
        })

        edit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            commitTemplate()   // 失焦：空白则恢复默认，并通知微信
        }
    }

    /**
     * 虚拟定位：开关 + 当前坐标（只读）+ 清除 + 抖动。
     *
     * 坐标不再手填，只由微信里的地图选点写入；抖动沿用与模板输入框同一套
     * 保存策略（边输入边 commit、失焦/暂停兜底），否则「改完直接退出」会丢改动。
     */
    private fun bindFakeLocation() {
        bindSwitch(R.id.switchFakeLocation, Prefs.KEY_FAKE_LOCATION, Prefs.DEFAULT_FAKE_LOCATION)
        bindFakeCoordDisplay()
        bindJitterEdit()
    }

    /**
     * 坐标只读展示 + 清除按钮。
     *
     * 坐标**不再手工填写**：只能去微信里长按「位置」图标用地图选点，
     * 选完由 `LocationPickedReceiver` 写进配置。这里只负责显示当前生效的是哪个点。
     */
    private fun bindFakeCoordDisplay() {
        fakeCoordText = findViewById(R.id.txtFakeCoord)
        refreshFakeCoord()

        findViewById<Button>(R.id.btnClearCoord).setOnClickListener {
            Prefs.local(this).edit()
                .putString(Prefs.KEY_FAKE_LAT, "")
                .putString(Prefs.KEY_FAKE_LNG, "")
                .commit()   // 同步落盘
            refreshFakeCoord()
            notifyHost()
            Toast.makeText(this, R.string.fake_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    /** 按配置里的经纬度刷新那一行只读文本。 */
    private fun refreshFakeCoord() {
        val tv = fakeCoordText ?: return
        val prefs = Prefs.local(this)
        val latD = prefs.getString(Prefs.KEY_FAKE_LAT, "")?.trim().orEmpty().toDoubleOrNull()
        val lngD = prefs.getString(Prefs.KEY_FAKE_LNG, "")?.trim().orEmpty().toDoubleOrNull()
        tv.text = if (latD == null || lngD == null) {
            getString(R.string.fake_coord_none)
        } else {
            String.format(Locale.US, "%.6f, %.6f", latD, lngD)
        }
    }

    /** 抖动存 Int（米）：与 Prefs 里标记的类型保持一致，镜像才不会丢。 */
    private fun bindJitterEdit() {
        val edit = findViewById<EditText>(R.id.editFakeJitter)
        jitterEdit = edit
        val prefs = Prefs.local(this)
        edit.setText(prefs.getInt(Prefs.KEY_FAKE_JITTER, Prefs.DEFAULT_FAKE_JITTER).toString())
        edit.setSelection(edit.text.length)

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.trim()?.toIntOrNull() ?: return
                prefs.edit().putInt(Prefs.KEY_FAKE_JITTER, value.coerceIn(0, 5_000)).commit()
            }
        })
        edit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            commitJitter()
            notifyHost()
        }
        fakeCommits += { commitJitter() }
    }

    private fun commitJitter() {
        val edit = jitterEdit ?: return
        val value = edit.text.toString().trim().toIntOrNull()?.coerceIn(0, 5_000)
        if (value == null) {
            edit.setText(Prefs.DEFAULT_FAKE_JITTER.toString())
            Prefs.local(this).edit().putInt(Prefs.KEY_FAKE_JITTER, Prefs.DEFAULT_FAKE_JITTER).commit()
            return
        }
        Prefs.local(this).edit().putInt(Prefs.KEY_FAKE_JITTER, value).commit()
    }

    /** 保存模板。commit() 同步落盘，防止紧接着被杀进程导致丢失。 */
    private fun saveTemplate(text: String, notify: Boolean) {
        runCatching {
            Prefs.local(this).edit()
                .putString(Prefs.KEY_RECALL_NOTICE_TEXT, text)
                .commit()   // 同步写盘：低频操作，安全性优先于性能
        }.onFailure { Logger.w("MainActivity", "保存模板失败：${it.message}") }
        if (notify) notifyHost()
    }

    /** 收尾保存：空白则恢复默认模板，并通知微信进程重读。 */
    private fun commitTemplate() {
        val edit = templateEdit ?: return
        val text = edit.text.toString()
        if (text.isBlank()) {
            edit.setText(Prefs.DEFAULT_RECALL_NOTICE_TEXT)
            saveTemplate(Prefs.DEFAULT_RECALL_NOTICE_TEXT, notify = true)
        } else {
            saveTemplate(text, notify = true)
        }
    }

    /**
     * 绑定一个开关，并让**标题文字颜色跟随开关状态**：开启=主色（黑），关闭=灰色。
     *
     * 之前所有标题都是同一个灰色，一眼看不出哪些功能开着。
     *
     * 标题/说明 TextView 没有 id，靠结构定位：Switch 的父布局是水平 LinearLayout，
     * 其中第一个子元素是垂直 LinearLayout，里面第 1 个是标题、第 2 个是说明。
     * 定位失败就只绑开关，不设色（不影响功能）。
     */
    private fun bindSwitch(id: Int, key: String, default: Boolean) {
        val sw = findViewById<Switch>(id)
        val (title, summary) = findTitleAndSummary(sw)

        val apply = { on: Boolean -> applySwitchStyle(sw, title, summary, on) }

        sw.isChecked = Prefs.local(this).getBoolean(key, default)
        apply(sw.isChecked)
        sw.setOnCheckedChangeListener { _, checked ->
            // 必须用 commit()（同步落盘）而不是 apply()：
            // 设置页通常一点完就被划掉杀进程，apply() 是异步的，写入可能还没
            // 刷到磁盘进程就没了 —— 微信下次冷启动读到的还是旧值，
            // 表现为「开关明明关了，重启微信却还在假定位」。
            Prefs.local(this).edit().putBoolean(key, checked).commit()
            apply(checked)
            notifyHost()
        }
    }

    /** 按「水平 LinearLayout -> 第 1 个垂直 LinearLayout -> 标题/说明」的结构定位。 */
    private fun findTitleAndSummary(sw: Switch): Pair<TextView?, TextView?> {
        val row = sw.parent as? ViewGroup ?: return null to null
        val column = (0 until row.childCount)
            .mapNotNull { row.getChildAt(it) as? ViewGroup }
            .firstOrNull { it !== sw && it.childCount >= 1 && it.getChildAt(0) is TextView }
            ?: return null to null
        val title = column.getChildAt(0) as? TextView
        val summary = (1 until column.childCount)
            .mapNotNull { column.getChildAt(it) as? TextView }
            .firstOrNull()
        return title to summary
    }

    /** 开=主色（黑），关=灰。夜间模式下自动取对应配色。 */
    private fun applySwitchStyle(
        sw: Switch,
        title: TextView?,
        summary: TextView?,
        on: Boolean,
    ) {
        val fg = if (on) R.color.mm_text_primary else R.color.mm_text_off
        val sm = if (on) R.color.mm_text_secondary else R.color.mm_summary_off
        title?.setTextColor(resources.getColor(fg, theme))
        summary?.setTextColor(resources.getColor(sm, theme))
        // 整体透明度也跟着变，弱化感更明显
        sw.alpha = if (on) 1f else 0.85f
    }

    /**
     * 通知微信进程配置变了。
     *
     * 用显式广播（setPackage）而不是隐式：Android 8+ 对隐式广播有后台限制，
     * 显式广播不受限；接收方是微信进程里动态注册的 Receiver。
     */
    private fun notifyHost() {
        runCatching {
            val intent = Intent(Prefs.ACTION_PREFS_CHANGED).setPackage(Prefs.WECHAT_PACKAGE)
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            sendBroadcast(intent)
        }.onFailure { Logger.w("MainActivity", "发送配置变更广播失败：${it.message}") }
    }
}
