package dev.mm.wxcj

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 模块自己的设置页。开关写进 SharedPreferences，微信进程通过 PrefsProvider 读取。
 *
 * 改开关后会发一条显式广播通知微信进程重读并热装载，因此**改完立刻生效，
 * 不必再重启微信**（前提：微信正在运行）。
 */
class MainActivity : Activity() {

    /** 模板输入框，供 onPause 兜底保存用。 */
    private var templateEdit: EditText? = null

    /** 虚拟定位的三个输入框，同样要在 onPause 兜底保存 */
    private var latEdit: EditText? = null
    private var lngEdit: EditText? = null
    private var jitterEdit: EditText? = null
    private val fakeCommits = ArrayList<() -> Unit>()

    /** 从一段文字里抠出数字，用于解析「39.909230,116.397428」或「lat 31.2;lng 121.4;」 */
    private val numberRegex = Regex("""-?\d+(?:\.\d+)?""")

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
     * 用 root 执行 force-stop。
     *
     * 依次尝试几种常见 su 调用形式：不同 root 方案（Magisk / KernelSU / APatch）
     * 对 `su -c "..."` 和 `su 0 <cmd>` 的支持程度不一样，多试几种能显著提高成功率。
     */
    private fun forceStopWeChat(): Pair<Boolean, String> {
        val pkg = Prefs.WECHAT_PACKAGE
        val commands = listOf(
            arrayOf("su", "-c", "am force-stop $pkg"),
            arrayOf("su", "0", "am", "force-stop", pkg),
            arrayOf("su", "-c", "killall $pkg"),
            arrayOf("su", "0", "pkill", "-f", pkg),
        )

        val details = ArrayList<String>()
        for (cmd in commands) {
            try {
                val process = Runtime.getRuntime().exec(cmd)
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val code = process.waitFor()
                val text = (stdout + "\n" + stderr).trim()
                if (text.isNotEmpty()) details += "${cmd.joinToString(" ")} → $text"
                if (code == 0) return true to ""
            } catch (t: Throwable) {
                details += "${cmd.joinToString(" ")} → ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        // 兜底：进程可能已经不在后台了，也算达成目的
        return false to details.take(3).joinToString("\n")
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
     * 虚拟定位：开关 + 纬度/经度 + 抖动 + 常用位置 + 粘贴。
     *
     * 与模板输入框同一套保存策略（边输入边 commit、失焦/暂停兜底），
     * 否则「改完直接退出」会丢改动。
     */
    private fun bindFakeLocation() {
        bindSwitch(R.id.switchFakeLocation, Prefs.KEY_FAKE_LOCATION, Prefs.DEFAULT_FAKE_LOCATION)

        latEdit = bindCoordEdit(R.id.editFakeLat, Prefs.KEY_FAKE_LAT, Prefs.DEFAULT_FAKE_LAT) { isValidLat(it) }
        lngEdit = bindCoordEdit(R.id.editFakeLng, Prefs.KEY_FAKE_LNG, Prefs.DEFAULT_FAKE_LNG) { isValidLng(it) }
        bindJitterEdit()
        bindPresetSpinner()
        bindPasteButton()
    }

    private fun bindCoordEdit(id: Int, key: String, default: String, valid: (Double) -> Boolean): EditText {
        val edit = findViewById<EditText>(id)
        val prefs = Prefs.local(this)
        // 先 setText 再挂监听，避免初始化那次被当成用户改动
        edit.setText(prefs.getString(key, default))
        edit.setSelection(edit.text.length)

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: return
                val value = text.toDoubleOrNull() ?: return
                if (!valid(value)) return
                prefs.edit().putString(key, text).commit()   // 同步落盘
            }
        })

        edit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            commitCoord(edit, key, default, valid)
            notifyHost()
        }
        fakeCommits += { commitCoord(edit, key, default, valid) }
        return edit
    }

    /** 空白或超出范围 → 恢复默认值，避免把非法坐标写进配置。 */
    private fun commitCoord(edit: EditText, key: String, default: String, valid: (Double) -> Boolean) {
        val value = edit.text.toString().trim().toDoubleOrNull()
        if (value == null || !valid(value)) {
            edit.setText(default)
            Prefs.local(this).edit().putString(key, default).commit()
            Toast.makeText(this, R.string.coord_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.local(this).edit().putString(key, value.toString()).commit()
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

    /** 常用位置：选中即写入两个输入框并立即通知微信进程（坐标是热生效的，不用重启）。 */
    private fun bindPresetSpinner() {
        val spinner = findViewById<Spinner>(R.id.spinnerPreset)
        ArrayAdapter.createFromResource(
            this,
            R.array.preset_locations,
            android.R.layout.simple_spinner_dropdown_item,
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) return
                val text = parent?.getItemAtPosition(position) as? String ?: return
                val (lat, lng) = parseCoordinate(text) ?: return
                applyCoordinate(lat, lng)
                spinner.setSelection(0)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** 从剪贴板里抠一对坐标填进去：地图 App「复制坐标」后直接粘过来。 */
    private fun bindPasteButton() {
        findViewById<Button>(R.id.btnPasteCoord).setOnClickListener {
            val text = runCatching {
                (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.primaryClip?.getItemAt(0)?.text?.toString()
            }.getOrNull()

            val coord = text?.let { parseCoordinate(it) }
            if (coord == null) {
                Toast.makeText(this, R.string.paste_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            applyCoordinate(coord.first, coord.second)
            Toast.makeText(
                this,
                getString(R.string.paste_done, coord.first.toString(), coord.second.toString()),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun applyCoordinate(lat: Double, lng: Double) {
        latEdit?.setText(lat.toString())
        lngEdit?.setText(lng.toString())
        Prefs.local(this).edit()
            .putString(Prefs.KEY_FAKE_LAT, lat.toString())
            .putString(Prefs.KEY_FAKE_LNG, lng.toString())
            .commit()   // 同步落盘
        notifyHost()
    }

    /**
     * 从文本里解析一对经纬度。
     *
     * 常见写法是「纬度,经度」，但也有反着来的；这里按取值范围自动判定顺序，
     * 两者都合法时按「纬度在前」处理。
     */
    private fun parseCoordinate(text: String): Pair<Double, Double>? {
        val nums = numberRegex.findAll(text).mapNotNull { it.value.toDoubleOrNull() }.toList()
        if (nums.size < 2) return null
        val a = nums[nums.size - 2]
        val b = nums[nums.size - 1]
        return when {
            isValidLat(a) && isValidLng(b) -> a to b
            isValidLat(b) && isValidLng(a) -> b to a
            else -> null
        }
    }

    private fun isValidLat(value: Double): Boolean = value >= -90.0 && value <= 90.0
    private fun isValidLng(value: Double): Boolean = value >= -180.0 && value <= 180.0

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
            Prefs.local(this).edit().putBoolean(key, checked).apply()
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
