package dev.mm.wxcj

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收微信进程回传的选点坐标。
 *
 * 选点发生在**微信进程**里，但配置存在**模块进程**的 SharedPreferences 中
 * （微信进程写不进去：Android 10+ `/data/data/<包名>` 是 700）。
 * 所以选完点后微信进程发一条显式广播，由这里在模块进程落盘。
 *
 * 落盘之后：设置页打开就能看到新坐标，下次微信冷启动 ContentProvider 也能读到。
 */
class LocationPickedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Prefs.ACTION_LOCATION_PICKED) return

        val lat = intent.getDoubleExtra(Prefs.EXTRA_LAT, Double.NaN)
        val lng = intent.getDoubleExtra(Prefs.EXTRA_LNG, Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return

        val pending = goAsync()
        runCatching {
            val editor = Prefs.local(context.applicationContext).edit()
                .putString(Prefs.KEY_FAKE_LAT, lat.toString())
                .putString(Prefs.KEY_FAKE_LNG, lng.toString())
            if (intent.getBooleanExtra(Prefs.EXTRA_ENABLE, true)) {
                editor.putBoolean(Prefs.KEY_FAKE_LOCATION, true)
            }
            editor.commit()   // 同步落盘：低频操作，安全优先

            // 顺手唤醒微信进程重读配置（模块进程起来后 Provider 才可用）
            val back = Intent(Prefs.ACTION_PREFS_CHANGED).setPackage(Prefs.WECHAT_PACKAGE)
            back.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            context.sendBroadcast(back)
        }.onFailure {
            android.util.Log.w("LocPicked", "保存选点坐标失败：${it.message}")
        }
        pending.finish()
    }
}
