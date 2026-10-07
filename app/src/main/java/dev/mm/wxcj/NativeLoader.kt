package dev.mm.wxcj

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * 装载 DexKit 的 native 库 libdexkit.so。
 *
 * 这是 Xposed 模块最容易踩的坑：模块运行在**宿主进程**里，而模块 APK 自带的 .so
 * 不在宿主的 nativeLibraryDirectories 中，所以 System.loadLibrary("dexkit")
 * 几乎必然抛 UnsatisfiedLinkError —— 表现为「什么都没发生，也不崩溃」。
 *
 * 正确做法：从模块 APK 里把 .so 解压到宿主私有目录，再用 System.load(绝对路径) 装载。
 */
object NativeLoader {

    private const val TAG = "Native"

    /** 已装载标记，避免重复解压。 */
    @Volatile
    private var loaded = false

    /** 返回 true 表示 libdexkit.so 可用。 */
    fun ensureDexKit(context: Context?): Boolean {
        if (loaded) return true

        // 路径一：宿主恰好能找到（少数框架会把模块 lib 目录并入搜索路径）
        runCatching {
            System.loadLibrary("dexkit")
            loaded = true
            Logger.i(TAG, "System.loadLibrary(\"dexkit\") 直接成功")
            return true
        }

        if (context == null) {
            Logger.e(TAG, "无 Context，无法从模块 APK 解压 libdexkit.so")
            return false
        }

        // 路径二：解压后绝对路径装载
        return runCatching {
            val so = extract(context, "dexkit")
            System.load(so.absolutePath)
            loaded = true
            Logger.i(TAG, "已装载 ${so.absolutePath}（${so.length()} 字节）")
            true
        }.onFailure { Logger.e(TAG, "装载 libdexkit.so 失败", it) }.getOrDefault(false)
    }

    /** 从模块 APK 中解压 lib/<abi>/lib<name>.so 到宿主 filesDir。 */
    private fun extract(context: Context, name: String): File {
        val out = File(context.filesDir, "wxcj_libs/lib$name.so")
        if (out.exists() && out.length() > 0) {
            Logger.i(TAG, "复用已解压的 ${out.absolutePath}")
            return out
        }
        out.parentFile?.mkdirs()

        val apk = moduleApkPath(context)
        Logger.i(TAG, "模块 APK：$apk")

        ZipFile(apk).use { zip ->
            val libEntries = zip.entries().toList()
                .filter { !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .map { it.name }
            Logger.i(TAG, "APK 内的 so：${libEntries.joinToString()}")

            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { cand ->
                libEntries.any { it == "lib/$cand/lib$name.so" }
            } ?: error("APK 内没有适配当前 ABI(${android.os.Build.SUPPORTED_ABIS.joinToString()}) 的 lib$name.so")

            val entry = zip.getEntry("lib/$abi/lib$name.so")!!
            zip.getInputStream(entry).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            Logger.i(TAG, "解压 lib/$abi/lib$name.so -> ${out.absolutePath}（${out.length()} 字节）")
        }
        return out
    }

    /**
     * 定位模块自己的 APK 路径。宿主进程里用 PackageManager 直接查会受
     * Android 11+ 包可见性限制，所以优先用 createPackageContext（系统级，不受限），
     * 最后兜底扫描 /data/app。
     */
    private fun moduleApkPath(context: Context): String {
        val candidates = LinkedHashSet<String>()

        runCatching {
            val mc = context.createPackageContext(Prefs.MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
            candidates += mc.packageCodePath
            mc.applicationInfo?.sourceDir?.let { candidates += it }
        }.onFailure { Logger.w(TAG, "createPackageContext 失败：${it.message}") }

        runCatching {
            candidates += context.packageManager
                .getApplicationInfo(Prefs.MODULE_PACKAGE, 0).sourceDir
        }.onFailure { Logger.w(TAG, "getApplicationInfo 失败：${it.message}") }

        val hit = candidates.firstOrNull { it.isNotBlank() && File(it).exists() }

        // 兜底：直接扫 /data/app
        val found = hit ?: run {
            File("/data/app").listFiles()?.asSequence()
                ?.filter { it.isDirectory && it.name.startsWith(Prefs.MODULE_PACKAGE) }
                ?.flatMap { dir ->
                    listOf(File(dir, "base.apk"), File(dir, "${Prefs.MODULE_PACKAGE}.apk"))
                        .asSequence() + (dir.listFiles()?.asSequence() ?: emptySequence())
                }
                ?.firstOrNull { it.exists() && it.name.endsWith(".apk") }?.absolutePath
        }

        return found ?: error("无法定位模块 APK，已尝试：$candidates 与 /data/app 扫描")
    }
}
