package dev.mm.wxcj

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.Locale

/**
 * 拉起**微信自己的**腾讯地图选点页，并把选中的坐标保存下来。
 *
 * 移植自 WeKit `FakeLocation.launchWechatLocationPicker()`（GPL-3.0）：
 *
 * ```
 * com.tencent.mm.plugin.location.ui.RedirectUI   # 微信的位置选择页
 *   putExtra("map_view_type", 8)                 # 8 = 选点模式
 * onActivityResult -> Intent.getParcelableExtra("KLocationIntent")
 *   -> 该对象某个返回 String 的方法 -> 正则 "lat 31.224;lng 121.469;"
 * ```
 *
 * 为什么不用自己画一张地图：腾讯地图的瓦片服务、POI 搜索、坐标系纠偏全在微信里，
 * 复用它的选点页既省事又能保证坐标与微信内部一致（GCJ-02），不会出现
 * 「选的点发出去偏了几百米」这种问题。
 */
object FakeLocationPicker {

    private const val TAG = "LocPicker"

    /** 与 WeKit 一致的请求码；微信自己的选点页回传结果时会原样带回来 */
    private const val REQUEST_CODE = 6

    private const val REDIRECT_UI = "com.tencent.mm.plugin.location.ui.RedirectUI"
    private const val EXTRA_K_LOCATION = "KLocationIntent"

    /** 结果串形如 `lat 31.224361;lng 121.469170;`（实际还会带其它字段） */
    private val COORD_REGEX = Regex("""lat ([-+]?[0-9]*\.?[0-9]+);lng ([-+]?[0-9]*\.?[0-9]+);""")

    /** 已 hook 过 RedirectUI.onActivityResult？微信里只需挂一次 */
    @Volatile
    private var hooked = false

    /** 我们主动拉起的选点页正在等待结果（用来区分微信自己发起的定位请求） */
    @Volatile
    private var pending = false

    /** 最近一次失败原因，诊断时直接显示给用户 */
    @Volatile
    var lastError: String? = null
        private set

    /** 选点成功次数（诊断用） */
    @Volatile
    var pickedCount = 0
        private set

    /**
     * 打开微信地图选点。
     *
     * @param context 任意上下文，内部会尝试提成 Activity
     * @param classLoader 宿主 ClassLoader（用来找 RedirectUI）
     * @return true 表示已经把选点页拉起来了
     */
    fun launch(context: Context?, classLoader: ClassLoader): Boolean {
        val activity = ActivityUtils.asActivity(context)
        if (activity == null) {
            fail("拿不到前台 Activity，请回到微信主界面再试")
            return false
        }

        val redirectClass = runCatching { XposedHelpers.findClass(REDIRECT_UI, classLoader) }
            .getOrNull() ?: runCatching { Class.forName(REDIRECT_UI, false, classLoader) }
            .getOrNull()

        if (redirectClass == null) {
            fail("没找到微信的选点页，可能版本不适配")
            return false
        }

        if (!ensureHooked(redirectClass)) {
            fail("无法监听选点结果（onActivityResult 没找到）")
            return false
        }

        return runCatching {
            pending = true
            val intent = Intent(activity, redirectClass)
            intent.putExtra("map_view_type", 8)
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQUEST_CODE)
            Logger.i(TAG, "已拉起微信选点页")
            true
        }.onFailure {
            pending = false
            fail("拉起选点页失败：${it.message}")
        }.getOrDefault(false)
    }

    /**
     * 挂上 `RedirectUI.onActivityResult`。
     *
     * 方法名是 Android 框架定义的、不会被混淆，但微信可能没直接重写，
     * 所以要沿父类找（[DexTargets.findMethodInHierarchy]）。
     */
    private fun ensureHooked(clazz: Class<*>): Boolean {
        if (hooked) return true
        val method: Method = DexTargets.findMethodInHierarchy(clazz, "onActivityResult") ?: return false

        return runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { handleResult(param.args) }
                        .onFailure { Logger.e(TAG, "处理选点结果出错", it) }
                }
            })
            hooked = true
            Logger.i(TAG, "已监听选点结果：${method.declaringClass.name}.onActivityResult")
            true
        }.onFailure { Logger.e(TAG, "hook onActivityResult 失败", it) }.getOrDefault(false)
    }

    private fun handleResult(args: Array<Any?>?) {
        if (!pending) return
        val requestCode = args?.getOrNull(0) as? Int ?: return
        if (requestCode != REQUEST_CODE) return
        pending = false

        val resultCode = args.getOrNull(1) as? Int ?: return
        if (resultCode != Activity.RESULT_OK) {
            Logger.i(TAG, "选点已取消（resultCode=$resultCode）")
            return
        }

        val data = args.getOrNull(2) as? Intent ?: return
        @Suppress("DEPRECATION")
        val locationIntent = runCatching { data.getParcelableExtra(EXTRA_K_LOCATION) as? Parcelable }
            .getOrNull()
        if (locationIntent == null) {
            fail("选点结果里没有位置数据（KLocationIntent），可能版本不适配")
            return
        }

        val raw = firstStringValue(locationIntent)
        if (raw.isNullOrBlank()) {
            fail("读不出选点结果，可能版本不适配")
            return
        }

        val match = COORD_REGEX.find(raw)
        val lat = match?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val lng = match?.groupValues?.getOrNull(2)?.toDoubleOrNull()
        if (lat == null || lng == null) {
            fail("解析坐标失败：$raw")
            return
        }

        applyPicked(data, lat, lng)
    }

    /**
     * 从 KLocationIntent 对象里拿位置串。
     *
     * WeKit 的做法是「第一个返回 String 的无参方法」—— 这个类被混淆了，
     * 只能靠返回值类型定位。这里多做一层兜底：先找无参 String 方法，
     * 再找任何参数个数为 0 且返回 String 的（含父类），都不行就读 toString。
     */
    private fun firstStringValue(obj: Parcelable): String? {
        var cursor: Class<*>? = obj.javaClass
        while (cursor != null) {
            cursor.declaredMethods.firstOrNull {
                it.parameterCount == 0 && it.returnType == String::class.java
            }?.let {
                it.isAccessible = true
                return runCatching { it.invoke(obj) as? String }.getOrNull()
            }
            cursor = cursor.superclass
        }
        Logger.w(TAG, "KLocationIntent 里没有返回 String 的方法，退回 toString")
        return obj.toString()
    }

    /** 保存坐标 + 打开开关 + 立刻在微信进程生效 + 通知模块进程落盘。 */
    private fun applyPicked(context: Context, lat: Double, lng: Double) {
        pickedCount++
        lastError = null

        // 1) 内存里立刻生效：hook 读的就是这两个字段，改完下一次取坐标就是新值
        FakeLocation.latitude = lat
        FakeLocation.longitude = lng
        FakeLocation.enabled = true

        // 2) 通知模块进程写进 SharedPreferences —— 这样设置页打开看到的就是新坐标，
        //    下次冷启动 ContentProvider 也能读到。
        runCatching {
            val intent = Intent(Prefs.ACTION_LOCATION_PICKED).setPackage(Prefs.MODULE_PACKAGE)
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            intent.putExtra(Prefs.EXTRA_LAT, lat)
            intent.putExtra(Prefs.EXTRA_LNG, lng)
            intent.putExtra(Prefs.EXTRA_ENABLE, true)
            context.sendBroadcast(intent)
        }.onFailure { Logger.w(TAG, "通知模块进程失败：${it.message}") }

        val text = String.format(Locale.US, "%.5f", lat) + ", " + String.format(Locale.US, "%.5f", lng)
        Logger.i(TAG, "已保存选点坐标：$text")
        Notifier.notify("虚拟定位已设为 $text")
    }

    private fun fail(reason: String) {
        lastError = reason
        Logger.w(TAG, reason)
        Notifier.notify("虚拟定位：$reason")
    }

    /** 诊断用 */
    fun report(): String =
        "选点入口：成功 $pickedCount 次" + (lastError?.let { "，最近失败：$it" } ?: "")
}
