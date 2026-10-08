package dev.mm.wxcj

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.random.Random

/**
 * 虚拟定位（移植自 WeKit `features/items/system/FakeLocation.kt`，GPL-3.0）。
 *
 * ## 原理（与 WeKit 完全一致）
 *
 * 微信的定位回调统一是 `onLocationChanged(TencentLocation, ...)`：
 * 腾讯定位 SDK 把结果塞进一个 `TencentLocation` 对象再交给微信。
 * 我们不需要伪造系统 GPS，只要在回调进来的那一刻拿到这个对象，
 * **hook 它的 `getLatitude()` / `getLongitude()`** 并直接返回设定值即可。
 *
 * 所以：
 *  - 不是系统级模拟位置，**不影响其他 App**，也不需要「模拟位置」权限；
 *  - 微信里所有走这套回调的地方（发送位置、附近的人/直播、小程序、朋友圈定位）
 *    都会拿到设定坐标；
 *  - 只在微信进程内生效，开关一关、重启微信即恢复真实定位。
 *
 * ## 三个回调目标
 *
 * WeKit 用三个 DexKit 指纹覆盖微信的几套定位监听（普通监听、WGS84 监听、
 * 默认管理器回调），本模块沿用同样的三个特征串，全部命中就全部 hook。
 *
 * ## 系统兜底
 *
 * 少数路径会直接问 Android 的 `LocationManager.getLastKnownLocation()`。
 * 这里也顺手接管（同样只作用于微信进程），命中次数在诊断里单列一行。
 */
object FakeLocation {

    private const val TAG = "FakeLocation"

    /** 默认坐标（上海人民广场附近），与 WeKit 默认值一致 */
    const val DEFAULT_LAT = 31.224361
    const val DEFAULT_LNG = 121.469170

    /**
     * 总开关：只有开关打开时才替换坐标。
     *
     * **默认必须是 false**：配置读取失败（模块进程没起来 / Provider 不通）时，
     * 兜底行为应该是「不伪造」，而不是「照着默认坐标伪造」。
     * 方向反了的话，用户会觉得「开关关了还在假定位」。
     */
    @Volatile
    var enabled = false

    /**
     * 坐标是否可用。
     *
     * 开关开着但坐标没填（或填的是非法值）时也必须走真实定位 ——
     * 「没坐标」和「关开关」是两个独立的关闭条件，缺哪个都不替换。
     */
    @Volatile
    var coordsReady = false

    @Volatile
    var latitude = DEFAULT_LAT

    @Volatile
    var longitude = DEFAULT_LNG

    /** 随机抖动半径（米）：0 = 关闭。让每次取到的坐标略有差异，避免完全固定值 */
    @Volatile
    var jitterMeters = 0

    @Volatile
    var verbose = false

    /** 微信定位回调触发次数（诊断用：0 说明回调没进来） */
    @Volatile
    var callbackCount = 0
        private set

    /** 坐标被替换的次数 */
    @Volatile
    var replacedCount = 0
        private set

    /** 系统 LocationManager 兜底命中次数 */
    @Volatile
    var systemFallbackCount = 0
        private set

    /** 已 hook 过的定位结果类，避免重复挂载 */
    private val hookedClasses = ConcurrentHashMap.newKeySet<String>()

    /**
     * 挂载微信自己的定位回调。
     *
     * @param methods DexKit 定位到的 `onLocationChanged` 候选（三个目标合并后的结果）
     * @return 成功挂载的方法数
     */
    fun install(methods: List<Method>): Int {
        var n = 0
        methods.forEach { method ->
            runCatching { hookCallback(method) }
                .onSuccess { n++ }
                .onFailure { Logger.e(TAG, "hook 失败：${method.declaringClass.name}.${method.name}", it) }
        }
        if (n == 0) Logger.w(TAG, "没有任何定位回调挂载成功")
        return n
    }

    private fun hookCallback(method: Method) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled) return
                callbackCount++
                // 不写死 args[0]：不同版本的回调签名参数顺序可能变，
                // 直接在参数里找「带 getLatitude/getLongitude 的那个对象」更稳。
                val location = param.args?.firstOrNull { it != null && looksLikeLocation(it) }
                if (location == null) {
                    if (verbose && callbackCount <= 3) {
                        val types = param.args?.joinToString { it?.javaClass?.name ?: "null" }
                        Logger.w(TAG, "回调 ${method.name} 参数里没找到定位结果对象：$types")
                    }
                    return
                }
                hookTencentLocation(location)
            }
        })
        Logger.i(TAG, "hook 已安装：${method.declaringClass.name}.${method.name}")
    }

    private fun looksLikeLocation(obj: Any): Boolean =
        obj.javaClass.name != "java.lang.String" &&
            DexTargets.findMethodInHierarchy(obj.javaClass, "getLatitude") != null &&
            DexTargets.findMethodInHierarchy(obj.javaClass, "getLongitude") != null

    /**
     * 接管定位结果对象的经纬度读取。
     *
     * **只 hook `getLatitude` / `getLongitude` 两个方法本身**，不去改对象里的字段 ——
     * 字段被混淆过、也可能在别处被直接读；而这两个 getter 是微信取坐标的唯一出口。
     */
    private fun hookTencentLocation(location: Any) {
        val clazz = location.javaClass
        if (!hookedClasses.add(clazz.name)) return

        val getter = DexTargets.findMethodInHierarchy(clazz, "getLatitude")
        val getterLng = DexTargets.findMethodInHierarchy(clazz, "getLongitude")
        if (getter == null || getterLng == null) {
            hookedClasses.remove(clazz.name)
            Logger.w(TAG, "${clazz.name} 里没有 getLatitude/getLongitude，跳过")
            return
        }

        val okLat = hookCoordinateGetter(getter) { jittered().first }
        val okLng = hookCoordinateGetter(getterLng) { jittered().second }
        if (!okLat || !okLng) {
            hookedClasses.remove(clazz.name)
            Logger.w(TAG, "${clazz.name} 的经纬度 getter 返回类型不是 double/float，跳过")
            return
        }

        Logger.i(TAG, "已接管定位结果类：${clazz.name}")
        if (verbose) Notifier.notify("虚拟定位：已接管 ${clazz.name}")
    }

    /**
     * hook 一个坐标 getter，让它直接返回设定值。
     *
     * 返回类型是 `double` 就塞 Double、`float` 就塞 Float；
     * 万一新版改成别的类型，宁可不动（返回 false），也不能塞错类型导致崩溃。
     */
    private fun hookCoordinateGetter(method: Method, value: () -> Double): Boolean {
        val returnType = method.returnType
        val isDouble = returnType == java.lang.Double.TYPE || returnType == Double::class.javaObjectType
        val isFloat = returnType == java.lang.Float.TYPE || returnType == Float::class.javaObjectType
        if (!isDouble && !isFloat) return false
        return runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!shouldReplace()) return
                    param.result = if (isDouble) value() else value().toFloat()
                    replacedCount++
                }
            })
            true
        }.onFailure { Logger.e(TAG, "hook ${method.name} 失败", it) }.getOrDefault(false)
    }

    /**
     * 系统兜底：接管微信进程里对 `LocationManager.getLastKnownLocation()` 的调用。
     *
     * 只作用于微信进程，其他 App 不受影响；与上面那条路径互相独立，
     * 两条都没有命中时，多半是特征串没定位到（见诊断提示）。
     */
    fun installSystemFallback(classLoader: ClassLoader): Boolean {
        return runCatching {
            val lmClass = XposedHelpers.findClass(LocationManager::class.java.name, classLoader)
            XposedHelpers.findAndHookMethod(
                lmClass,
                "getLastKnownLocation",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!shouldReplace()) return
                        val provider = param.args?.getOrNull(0) as? String ?: return
                        val (lat, lng) = jittered()
                        val loc = Location(provider)
                        loc.latitude = lat
                        loc.longitude = lng
                        loc.accuracy = 20f
                        loc.time = System.currentTimeMillis()
                        runCatching { loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() }
                        param.result = loc
                        systemFallbackCount++
                    }
                },
            )
            Logger.i(TAG, "系统定位兜底已挂载：LocationManager.getLastKnownLocation")
            true
        }.onFailure { Logger.w(TAG, "系统定位兜底未挂载：${it.message}") }.getOrDefault(false)
    }

    /**
     * 是否该替换坐标：**开关打开 且 坐标有效**，两个条件缺一不可。
     *
     * 之所以把「坐标有效」也做成硬条件，是因为用户最容易遇到的困惑就是
     * 「开关关了怎么还是假定位」。让它俩独立生效，任何一条不成立都走真实定位。
     */
    private fun shouldReplace(): Boolean = enabled && coordsReady

    /** 加上随机抖动后的坐标。抖动关闭时就是设定值本身。 */
    private fun jittered(): Pair<Double, Double> {
        val radius = jitterMeters
        if (radius <= 0) return latitude to longitude
        val metersPerDegreeLat = 111_320.0
        val scale = cos(Math.toRadians(latitude)).let { if (it < 0.01) 0.01 else it }
        val dLat = (Random.nextDouble() * 2 - 1) * radius / metersPerDegreeLat
        val dLng = (Random.nextDouble() * 2 - 1) * radius / (metersPerDegreeLat * scale)
        return (latitude + dLat) to (longitude + dLng)
    }

    /** 诊断用：一眼看出回调有没有进来、坐标有没有被换掉。 */
    fun report(): String {
        val classes = hookedClasses.let { if (it.isEmpty()) "无" else it.joinToString("、") }
        val lat = String.format(Locale.US, "%.5f", latitude)
        val lng = String.format(Locale.US, "%.5f", longitude)
        val jitter = if (jitterMeters > 0) "（抖动 ±${jitterMeters}米）" else ""
        val state = when {
            !enabled -> "开关关闭（不替换）"
            !coordsReady -> "坐标未设置（不替换）"
            else -> "生效中"
        }
        return "虚拟定位：$state，回调 $callbackCount 次，替换 $replacedCount 次，" +
            "系统兜底 $systemFallbackCount 次，接管类=$classes，坐标=$lat,$lng$jitter"
    }
}
