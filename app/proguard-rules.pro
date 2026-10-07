# Xposed 入口类不能被重命名，assets/xposed_init 里按全名引用。
-keep class dev.mm.wxcj.HookEntry { *; }

# DexKit 使用 JNI，相关类与 native 方法必须保留。
-keep class org.luckypray.dexkit.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# 反射定位到的宿主类不参与本模块混淆（compileOnly 依赖本就不打包，此处仅保险）。
-dontwarn de.robv.android.xposed.**
-dontwarn org.luckypray.dexkit.**
