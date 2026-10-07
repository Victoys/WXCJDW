# MM

包名 `dev.mm.wxcj`，桌面显示名 `MM`，版本 v3.12。

**轻量模块**：只做 4 个「hook 一个返回值」的功能，不涉及任何 UI / 菜单注入，
目标是尽可能不给微信启动添负担。重功能（伪装余额、修改文本消息、视频号下载）
在另一个独立模块 **MM Plus** 里，两个模块互不依赖，装哪个用哪个。

## 功能清单

| 功能 | 默认 | 移植来源 | 原理 |
|---|---|---|---|
| 消息防撤回 | 开 | `AntiMessageRecall.kt`（633 行） | 把 sysmsg 解析结果里的 `.sysmsg.$type` 从 `revokemsg` 置空 |
| 禁止上传「正在输入」 | 开 | `DisableTypingStatusUploading.kt` | `doScene()` 前把返回值置 `-1`，请求发不出去 |
| 禁用拍一拍 | 关 | `DisablePat.kt` | 双击回调返回 `true`，表示事件已消费 |
| 虚拟定位 | 关 | `features/items/system/FakeLocation.kt` | 接管定位结果对象的 `getLatitude()` / `getLongitude()`，返回设定坐标 |
| 虚拟定位·地图选点 | 随虚拟定位 | 同上（+ `WeChatLocationEntry` / `FakeLocationPicker`） | 长按聊天面板「位置」图标，拉起微信自己的腾讯地图选点，选完即生效 |

来源仓库 [WeKit](https://github.com/Ujhhgtg/WeKit)（GPL-3.0）。

### 为什么只放这 4 个

4 个功能全部是「改一个返回值」，微信内部怎么重构都相对稳；而 UI/菜单注入类功能
（长按菜单、分享菜单、微信内置地图选点）跨版本风险明显更高。分开之后：
只想要防撤回的人，不必承担菜单注入带来的启动开销与兼容风险。

## 虚拟定位（v3.11 新增，v3.12 加入微信地图选点）

开关打开后，微信里所有走定位回调的地方都会拿到设置页填的坐标：发送位置、
附近的人/直播、小程序定位、朋友圈定位等。

### 怎么用微信自己的地图选点（不用手填坐标）

在微信任一聊天里点「+」打开面板 → **长按「位置」图标** →
弹出微信自己的腾讯地图选点页 → 地图上长按/拖动选点 → 确定。

选完即保存、自动打开开关并**立刻生效**（坐标是热更新的，不用重启微信），
设置页再打开看到的就是这个新坐标。

这是唯一入口 —— 不往微信设置页插任何东西（8.0.7x 设置页已重构，
插行方案基本必失败且徒增扫描开销），模块本体仍保持「不注入 UI」的原则。

这条路径移植自 WeKit `FakeLocation.launchWechatLocationPicker()`：拉起
`com.tencent.mm.plugin.location.ui.RedirectUI`（`map_view_type = 8`），
在 `onActivityResult` 里取 `KLocationIntent` 解析出 `lat …;lng …;`。
因为用的是微信自己的地图，坐标系与微信内部一致（GCJ-02），不会出现
「选的点发出去偏了几百米」。

**仍然保留手填**：模块设置页里可以手填经纬度、选常用位置、粘贴剪贴板坐标。
入口装不上时就用它兜底。

几点要说明：

- **不是系统级模拟位置**：只 hook 微信进程内的定位回调与 `LocationManager.getLastKnownLocation()`，
  其他 App 拿到的一直是真实位置；也不需要开「模拟位置」权限。
- **坐标可以点选也可以手填**：首选长按微信面板「位置」图标用微信自己的地图选点；
  也可以手填纬度/经度，或用「常用位置」下拉、剪贴板「粘贴」按钮
  （在高德/百度地图里长按取点 → 复制坐标 → 回来点粘贴）。
- **改坐标即时生效**：开关与坐标都是在 hook 内部读取的，改完发广播热更新，
  **不必重启微信**（这一点与其他功能不同，其他功能关掉需要重启）。
- **随机抖动**：可填一个半径（米），每次取坐标时在半径内随机偏移，避免每次都是完全相同的数值。
  默认 0（关闭）。
- **关掉开关**就不再替换坐标，微信恢复正常定位。

排查时开**详细诊断**，自检④ 会多一条：

```
虚拟定位：回调 N 次，替换 M 次，系统兜底 K 次，接管类=xxx，坐标=xx,xx
```

- 回调 0 次：三个定位回调一个都没定位到（或这次没触发过定位），看「自检③」里有没有虚拟定位；
- 回调有了、替换 0 次：定位结果对象上没有 `getLatitude`/`getLongitude`，需要按新版微信重抓；
- 替换有了但位置没变：微信这个入口用的不是这套回调，靠系统兜底那条路径（看 K 的次数）。

选点入口的诊断另有一行：`选点入口：已挂载 ✓，长按 N 次，拉起 M 次`。
- N=0：长按监听没挂上（要么 AppGrid.getView 没定位到，要么没认出「位置」图标）；
- N>0 但 M=0：认出格子的那一步没成功（图标资源名可能变了）；
- M>0：选点页已经拉起来过，问题在结果解析（看 `虚拟定位：选点` 那条提示）。

### 精简掉的部分

- **撤回角标 UI**：WeKit 会在被保留的气泡旁画「已撤回」红色角标（三百多行 View 操作），本模块不做 —— 消息照常保留，只是没有视觉标记。
- **自己撤回的拦截**：本设备主动撤回走 `NetSceneRevokeMsg`，不经 sysmsg，与 WeKit 一致不处理。
- **微信设置页里插一行**：WeKit 的 `WeSettingsInjector` 那套（往 `SettingsUI` / 新版
  `SettingGroupMain` 里插 Preference）。8.0.7x 已重构设置页，插行方案基本必失败，
  而且要多扫 7 个 dex 目标、拖慢启动。选点只走长按面板「位置」图标这一条路。

## 启动性能

做了三件事，确保不拖慢微信：

1. **后台线程解析**：hook 装在独立线程，不占主线程。
2. **按微信版本缓存**：解析结果存进宿主进程，微信不升级就不重扫。
3. **缓存命中时不加载 .so**：缓存全命中时连 `libdexkit.so` 都不装载，开销接近零。

只有**首次启用**（或微信升级后）才会在后台跑一次 DexKit 解析，那时微信照常使用，不受影响。

## 可见提示（不再静默）

关键节点都会在微信界面上弹一条提示，**3 秒后自动淡出消失**：

| 时机 | 提示内容 |
|---|---|
| 每次冷启动微信 | `MM 已注入 · N 个功能已启用` |
| 本次真的扫了 dex 之后 | `MM 解析完成（X 秒）· N 个目标全部就绪`（不完整时会显示 `成功 N/5，部分功能可能不可用`） |

命中缓存时不会弹第二条 —— 那本来也没让你等。

实现上优先在前台 Activity 的 DecorView 上贴浮层（时长精确可控、无需权限）；
拿不到前台窗口时退回 Toast。提示可能发生在第一个 Activity 出现之前，
所以会先入队，等 Activity 出现后统一显示。

不想要的话，设置页关掉**启动提示**开关即可恢复静默。

## 在 GitHub Actions 里构建

1. 推到自己的 GitHub 仓库（保证 `gradle/wrapper/gradle-wrapper.jar` 一起提交）。
2. push 到 main/master 即构建，产物在 Artifacts，约 3–6 分钟。
3. 发 Release：`git tag v3.12 && git push origin v3.12`。

产物：`MM-v3.12.apk`（release 复用 debug 签名，可直接安装）。

### 报错 `resource mipmap/ic_launcher not found`

Manifest 引用了 `@mipmap/ic_launcher`，但 `app/src/main/res/` 下没有对应的图标。
同样是「二进制资源在纯文本同步中丢失」造成的。

本项目把图标改成了**纯 XML 的自适应图标**（`minSdk = 26`，而自适应图标正好从 API 26
起支持，所以一份 `mipmap-anydpi-v26/ic_launcher.xml` 就够，不需要任何密度的 PNG）：

```
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml   自适应图标定义
app/src/main/res/drawable/ic_launcher_background.xml 底色（跟随日/夜间模式）
app/src/main/res/drawable/ic_launcher_foreground.xml 气泡图形（vector）
```

CI 里也加了 `Ensure launcher icon` 一步：图标缺失时自动重建这三个文件，不必手动处理。

另外 manifest 里的 `android:extractNativeLibs` 已移除（AGP 8 会警告），
行为由 `build.gradle.kts` 的 `jniLibs.useLegacyPackaging = true` 保持，
`libdexkit.so` 依旧是解压安装、可被 `System.loadLibrary` 找到的。

### 报错 `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`

这是仓库里**缺 `gradle/wrapper/gradle-wrapper.jar`**（只有 `gradlew` 脚本和
`gradle-wrapper.properties`）。`gradlew` 实际跑的就是这个 jar 里的主类，
jar 不在就直接崩，跟代码无关。

jar 是二进制文件，纯文本的源码快照带不出来，所以两种补法：

- **CI 自动补齐（已内置）**：workflow 里加了 `Ensure Gradle wrapper jar` 一步，
  检测到 jar 缺失就从 Gradle 官方分发包里取出同版本的 jar 放回去，无需手动干预。
- **本地补**：在项目根目录跑一次 `gradle wrapper --gradle-version 8.9`
  （或直接从分发包里取），然后把生成的 `gradle/wrapper/gradle-wrapper.jar` 提交上去。

## 使用

1. 装好 LSPosed / EdXposed 与本 APK。
2. 框架里勾选本模块，作用域选微信，**强制停止并重新打开微信**。
3. 桌面「MM」图标调开关，再重启生效（也可在 LSPosed 中长按模块 → 打开应用）。
4. 排查看 LSPosed 日志，过滤 `Wxcj`。

### 测试方法

- **防撤回**：只能验证**对方**撤回的消息。自己发的消息撤回不经 sysmsg，用自己的消息测试必然「没效果」。让好友发一条再撤回，消息应保留（没有「已撤回」角标）。
- **禁止输入状态**：让对方观察你在输入时他那边是否还显示「对方正在输入」。
- **禁用拍一拍**：双击好友头像，不再出现拍一拍。
- **虚拟定位**：在任一聊天里点「+ → 位置 → 发送位置」，地图上定位到的点应变成设置页填的坐标
  （注意微信会优先用缓存的旧位置，必要时回到主界面再进一次）。启用**详细诊断**可以直接看
  「回调 N 次 / 替换 M 次」。

### 没生效怎么办

| 日志 | 含义 |
|---|---|
| 一条都没有 | 模块没被加载，检查勾选 + 作用域 + 强制停止重开 |
| 只有「跳过进程：xxx」 | 模块加载了但进程名不匹配 |
| `libdexkit.so 不可用` | .so 没装载成功 |
| `未定位到 xxx` / `xxx 已开启但未定位到目标` | 微信版本超出支持区间，需重抓特征串 |
| `hook 已安装` | 已生效 |

## 排查：功能都没生效怎么办

先打开设置页的**详细诊断**开关，完全重启微信，会依次弹出 4 条自检结果：

| 提示 | 说明 |
|---|---|
| 自检① hook 链路正常/异常 | 不依赖 DexKit，纯反射 hook `LauncherUI.onResume`。**异常**说明注入本身有问题（框架、作用域、重启没做到位），跟特征串无关 |
| 自检② 已扫 dex / 命中缓存 | 命中缓存说明本次没重新扫描，功能应直接用缓存结果 |
| 自检③ 一个目标都没定位到 | 问题在 DexKit（so 装载或扫描源），不是特征串 |
| 自检③ 未定位到 → 某某 | 只有这几个功能的特征串失效，需按新版微信重抓 |
| 自检④ hook 回调已触发 N 次 | 回到微信主界面才会触发。N>0 说明注入完全正常，问题只在特征串 |

另外三个高频误判：

1. **防撤回必须用「对方」撤回的消息测**。自己发自己撤回走 `NetSceneRevokeMsg`，不经 sysmsg，模块本来就不处理，测出来必然无效。
2. **禁用拍一拍默认是关的**，要在设置页打开。
3. **改了开关必须完全重启微信**（从最近任务划掉再打开），热重启不生效。

改过实现或换了微信版本后，点设置页的**重新解析微信**按钮，下次启动会丢弃缓存重扫。

## 配置读取为什么有时失败

模块配置存在模块自己的 data 目录，微信进程要去读它只有两条路：

| 方式 | 说明 |
|---|---|
| ContentProvider | 走 Binder，系统代理读取。**但依赖模块进程能被拉起**：首次查询时系统要启动模块进程，被 ROM 的省电/冻结策略挡住或启动慢就会拿到 null |
| XSharedPreferences | 读 `/data/data/dev.mm.wxcj/shared_prefs/`，Android 10+ 该目录是 700，微信进不去，基本只作回退 |

所以加了第三条：**宿主侧镜像**。每次成功读到真实配置后，往宿主自己的目录写一份。
下次冷启动若 Provider 暂时不可用，先用镜像启动功能，再由后台线程按
0/0.5/1.5/3/6/10 秒的间隔重试，成功后**热装载**缺的功能并提示「已补装」。

启动提示里的「自检⓪：配置来源」会直接告诉你这次用的是哪种：

- `ContentProvider` —— 正常
- `宿主侧镜像` —— Provider 暂不可用，功能用上次的配置启动了，后台在重试
- `默认值（读取失败）` —— 镜像也没有（通常是从未成功读过一次），后台同样在重试

## 配置读取不稳定的真正原因与对策

ContentProvider 只有在**模块进程活着**时才可用。直接冷启动微信时，模块进程通常
还没起来（或已被 ROM 冻结），query 返回 null → 全部回退默认值 → 所有功能失效。
这也解释了为什么「先打开一次 MM 设置页再重启微信」就正常。

三层对策：

| 层 | 做法 |
|---|---|
| 主动唤醒 | 微信进程读配置前先发**显式广播** `dev.mm.wxcj.WAKE` + `FLAG_INCLUDE_STOPPED_PACKAGES` 把模块进程拉起（显式广播不受 Android 8+ 隐式广播后台限制），等待后重试查询，共 8 次、间隔递增 |
| 宿主侧镜像 | 每次成功读到真实配置就往宿主目录写一份，下次冷启动 Provider 暂不可用时先用它启动功能 |
| 变更推送 | 设置页改开关 → 广播 `dev.mm.wxcj.PREFS_CHANGED` → 微信进程动态注册的 Receiver 收到后立即重读并热装载，**改完即生效，不必重启微信** |

注意：热装载只能「补开」，关掉某个开关仍需重启微信生效 —— Xposed 的 hook 无法安全卸载。

若仍不稳定，在系统设置里把 **MM** 加入电池优化白名单 / 自启动名单，避免模块进程被冻结。

## 已移除：朋友圈禁止自动播放

该功能已整体删除（`DisableSnsVideo.kt`、`DexTargets` 里 6 个定位目标、
`Prefs` 开关、设置页控件与文案）。

原因：三次尝试都没能验证成功 —— 特征串定位到的两个判定点 hook 上了但没有实际效果，
新增的 4 个按方法名定位的目标（`isAutoPlay` / `canAutoPlay` / `shouldAutoPlay` /
`enableAutoPlay`）在当前微信版本里**全部未定位到**。
且本环境无法访问 WeKit 源码做对照，继续猜下去没有依据。

现在模块共 4 个功能：消息防撤回、禁止上传「正在输入」、禁用拍一拍，以及后加入的虚拟定位。

## 自己撤回的消息默认不拦截（含判定策略说明）

新增开关**「也拦截自己撤回的」**，默认**关**。关闭时自己发出又撤回的消息会被**放行**，
微信正常把它撤掉，也不弹任何提示。

### 两条路径的判定策略是相反的

| 路径 | 会走哪些撤回 | 可靠信号 | 查不到时 |
|---|---|---|---|
| sysmsg | **只有对方的** | `replacemsg` 以「你撤」开头即自己；自己撤回根本不经过 XmlParser | 按「对方」处理 → 拦截 |
| doRevokeMsg | 自己的和对方的都走 | 只能靠快照里的 `isSend` 反查 | **按「自己」处理 → 放行** |

之所以相反，是因为两者能拿到的信息不一样：

- WeKit 源码明确写了「recalling a message sent from this device **never parses a
  revoke sysmsg**」—— 自己撤回走 `NetSceneRevokeMsg` 直接改写本地消息行，
  压根不经过 XmlParser。所以到达 sysmsg 路径的一定是别人撤的，可以放心拦截。
- doRevokeMsg 路径拿不到 `replacemsg`，只能查 `isSend`。而 `isSend` **经常查不到**：
  自己发出去的消息 `msgSvrId` 是服务器返回后才赋值的，`setContent` 那一刻还是 0，
  快照直接跳过，于是 `isSelf()` 返回 null。

v2.6 把 null 当「不是自己」→ 拦截并弹提示，正是「自己撤回也被拦截」的原因。
v2.7 反过来：**只有明确查到 `isSend=false` 才拦截**，查不到就放行。
宁可放过一条，也不该把自己主动撤回的消息硬留下来。

### 顺带修的根因

`MsgSnapshots` 现在会挂 `msgSvrId` 的 setter（若该版本能定位到）：
`setContent` 时先按 **MsgInfo 实例** 暂存内容，等 id 真正赋值后再落库。
这样自己发出的消息也能建立快照，isSend 判定随之变准。

日志里会打印 `isSend 读取方式：…` 与 `msgSvrId 写入方式：…`，
显示「未找到」即表示该版本只能靠文本兜底。

## 自己撤回的消息默认不拦截

新增开关**「也拦截自己撤回的」**，默认**关**。关闭时：

- 自己发出又撤回的消息 → **放行**，微信正常把它撤掉（这正是用户想要的结果）
- 不弹任何拦截提示

判定「是不是自己发的」用两个信号，任一成立即生效：

1. sysmsg 的 `replacemsg` 以「你撤」开头 —— 微信对自己撤回固定用「你撤回了一条消息」，
   对别人则是「「张三」撤回了一条消息」，这个区分长期稳定。
2. 快照表里该 `msgSvrId` 的 `isSend` 为真（`MsgSnapshots.isSelf`）。

两个都拿不到时按「对方撤回」处理 —— 宁可多拦一条，也不漏拦。

这与 WeKit 的 `recallOutgoing`（默认 false）一致。

## v3.7：消除两类误导性日志

### 1. 「hook 已触发 N 次，未遇到撤回指令」刷屏

切设置、刷朋友圈时会频繁弹出。它不是报错，只是诊断模式下每 20 秒一条的
「hook 已通」确认 —— 而 XmlParser 会被各种 sysmsg 频繁调用，于是成了噪音。

现在**只提示一次**，措辞也改成明确的确认句，后续只写日志。

### 2. 浮层正常显示时仍打「等待前台窗口超时」

`scheduleTimeout` 一旦排定就不再判断当前状态，于是浮层明明在显示，
仍会打超时降级。现在已拿到前台或正在显示时直接跳过降级。

另外 flush 成功时会清掉陈旧的失败原因，避免诊断里显示早已过时的
「最近失败：…」（3.6 日志里出现过这种自相矛盾的记录）。

## v3.6：浮层出不来 + 按会话兜底从未建立

3.5 日志里微信明明在前台放了 3 分钟，却反复：

```
WARN 等待前台窗口超时，降级为 Toast
```

说明单靠 hook `Activity.onResume` 拿不到可用的前台 Activity。改为**双保险**：

1. 主：`Application.registerActivityLifecycleCallbacks`（系统直接分发，官方 API）；
2. 备：保留原来的 Xposed hook。

并在 flush 失败时记录**具体原因**，诊断里多一条：

```
浮层：lifecycle=已注册，hookConfirmed=true，showing=false，待显示=2条，最近失败：…
```

另一处：`按会话 0 个` 说明**按会话兜底从未建立** —— 设 content 时 `field_talker` 还是空的。
现在也识别「这次设的是 talker」的 setter，在它触发时把内容关联到该会话，
两种赋值顺序都能覆盖。

## v3.5：setMsgId 不是 msgSvrId

3.4 日志里一切正常，唯独：

```
msgSvrId 写入：方法 setMsgId()
内容快照：可用=true，缓存 0 条
```

`msgId`（本地自增 ID）和 `msgSvrId`（服务器 ID）在微信里是**两个不同的东西**。
匹配条件放宽到 `msgid`/`msgId` 后挂上了 `setMsgId()`，于是把本地 ID 当成服务器 ID
存进缓存。而撤回 sysmsg 里的 `newmsgid` 是服务器 ID，两者永远对不上 ——
**精确索引一条都没建起来**，内容全靠「最近一条」兜底（就是那个 `〔可能〕` 前缀）。

修复：只认 `svrId` / `svrid`。找不到就不挂（宁缺勿错），内容仍有兜底。

顺带：

- `doRevokeMsg` 挂到非 `com.tencent.mm` 开头的类时**明确告警**
  （8.0.78 实测挂到 `b41.t.c`，调用 0 次，是空转）；
- 浮层 flush 时跳过正在销毁的 Activity，等下一个 `onResume` 而不是急着降级成 Toast
  （日志里出现过反复超时）。

## v3.3：诊断计数在后台会全 0，是假象

实测日志里出现过：

```
探针 A=0 B=0 C=0｜ClassLoader相同=true
等待前台窗口超时，降级为 Toast
```

看起来像 hook 全挂了，但同一份日志里 `SelfTest 自检回调第 1 次触发` ——
hook 明明是通的。

原因是诊断在**启动后固定 sleep 20 秒**就统计。若这 20 秒里微信还在后台
（没进前台界面），所有回调计数自然都是 0，纯属假象。

改为：**先等微信真正进入前台**（`Notifier.waitForForeground`，最多 60 秒），
再等 20 秒统计。超时未进前台时明确提示「以下计数全部无效」，不再误导。

## v3.2：方法名也被混淆了

v3.1 的诊断：`扫到 com.tencent.mm.storage.e9 但没有 setContent(String)`。

**枚举奏效了** —— 找到了真实的消息存储类 `com.tencent.mm.storage.e9`（混淆名），
它有 talker、有 msgSvrId，就是**没有 setContent** —— 8.0.78 把方法名也一起混淆了。

结论：**不能依赖任何方法名**。改成：

| 原来 | 现在 |
|---|---|
| 按名字找 `setContent(String)` | 找 `content` **字段**（`field_content`，DB 列名映射通常保留） |
| 挂一个 setter | 把**所有单 String 参数的 setter 全挂上** |
| 回调直接用入参 | 回调里校验 `字段值 == 入参` 才认定是内容 |

最后那条校验是关键：否则 `setTalker` / `setImgPath` 这类 setter 也会被当成内容写入，
把「最近一条」污染成别的东西。

`setContent` 不存在时，`msgSvrId` 的 setter 回调里也会直接读 content 字段补建，
覆盖「先设 id 再设内容」的顺序。

## v3.1：改用 dex 枚举定位消息存储类

v3.0 的诊断显示 6 个写死候选类（`storage.MsgInfo`、`storage.bi`、`g.c.eo`、
`g.c.dy`、`g.c.ei`…）**全部「无此类」** —— 微信 8.0.78 把这个类挪走了。

继续往名单里加名字是猜谜，永远追不上版本变化。所以新增 `HostClassScanner`：
**去宿主 dex 里把类名枚举出来**，再按特征打分挑出真正的消息存储类。

| 步骤 | 做法 |
|---|---|
| 枚举 | `BaseDexClassLoader.pathList.dexElements[].dexFile.entries()`；失败则读 `sourceDir + splitSourceDirs` 用 `DexFile.loadDex` |
| 过滤 | 只保留 `com.tencent.mm.g.c.*`、`com.tencent.mm.storage.*`、名字含 msginfo 的 |
| 打分 | 有 `setContent(String)` +1，有 talker +1，有 msgSvrId +1 |
| 选取 | 满分直接用；2 分作为降级候选 |

诊断会带上实际选中的类名（`类=xxx`）。

## 提示里显示「被撤回的内容」

### v3.0 修复：快照根本没装起来

诊断 `内容快照：可用=false，缓存 0 条` 说明**数据源压根不存在**，不是查询失败。

根因是一个反射 bug：

```kotlin
clazz.declaredMethods.firstOrNull { ... }   // ❌ 不含继承来的方法
```

微信的 `MsgInfo` 是代码生成的，`setContent` 常在生成器基类（如
`com.tencent.mm.g.c.*`）里。只查当前类必然定位失败，整个快照功能静默降级。

修复：

1. `setContent` / `msgSvrId` setter / `isSend` / talker **全部沿继承链查找**；
2. 候选类名改为列表（`storage.MsgInfo`、`storage.bi`、`g.c.eo` 等）逐个试；
3. 全部未命中时，日志打印候选类的 String 单参方法名，便于一次定位；
4. 诊断里直接带失败原因：`可用=false，原因：候选类全部未命中：xxx`。

### 三级查询

| 级别 | 依据 | 显示 |
|---|---|---|
| 1 | 撤回 sysmsg 里的 `msgid` 命中快照 | 内容（准确） |
| 2 | 同一会话（talker）最近一条消息 | `〔可能〕内容` |
| 3 | 全局最近一条消息（120 秒内） | `〔可能〕内容` |
| — | 都没有 | `(未知内容)` |

第 1 级依赖 `msgSvrId`，而它在 `setContent` 那一刻**可能还是 0**（id 由服务器
返回后才写入）。所以 `setContent` 时**无条件**记录「最近一条」，id 确定后再补建
精确索引。兜底命中时加 `〔可能〕` 前缀，避免把不确定的内容当事实。

## 提示里显示「被撤回的内容」：三级查询

撤回提示里的 `$content` 来自本地快照，按三级顺序取：

| 级别 | 依据 | 显示 |
|---|---|---|
| 1 | 撤回 sysmsg 里的 `msgid` 命中快照 | 内容（准确） |
| 2 | 同一会话（talker）最近一条消息 | `〔可能〕内容` |
| 3 | 全局最近一条消息（120 秒内） | `〔可能〕内容` |
| — | 都没有 | `(未知内容)` |

### 为什么需要第 2、3 级

第 1 级依赖 `msgSvrId`，而它在 `MsgInfo.setContent` 那一刻**可能还是 0** ——
微信是先构造对象再赋值 id 的，id 由服务器返回后才写入。之前代码遇到 `id <= 0`
直接 return，于是快照永远建不起来，只能显示「未知内容」。

现在改成：`setContent` 时**无条件**记录「最近一条」（含按 talker 分组），
id 确定后再补建精确索引。所以即使这个版本拿不到 `msgSvrId`，内容也显示得出来。

### 兜底为什么加「可能」

撤回通常紧跟在被撤消息之后，所以「最近一条」多数情况下就是被撤的那条；
但撤回**很久以前**的旧消息时它可能已经不对了。超过 120 秒就放弃兜底，
命中兜底时加 `〔可能〕` 前缀，避免把不确定的内容当事实。

### 排查

开详细诊断，重启微信后有一条：

```
内容快照：可用=true，缓存 N 条，最近=xxx（M秒前），按会话 K 个
```

- `可用=false` → `MsgInfo` 或 `setContent` 没定位到，整个快照不可用
- `最近=无` → hook 装了但从没被调用（该类不是消息存储入口）
- `最近=有` 但提示仍是「未知内容」→ 超过 120 秒窗口，或 talker 没取到

## 自定义提示模板的保存时机

设置页的模板输入框此前只在 **失焦** 时保存，而**关闭 Activity 并不保证会让
EditText 失焦**（软键盘还开着时直接返回/上滑关闭，焦点变化根本不触发），
于是改动永远写不进 SharedPreferences，重开 App 又是默认值。

现在三层保险：

1. `TextWatcher` 每改一个字符就 `commit()`（**同步**落盘，`apply()` 异步有丢的风险）；
2. `onPause()` 再兜底保存一次；
3. 失焦/暂停时才发广播通知微信进程，避免每敲一个字就广播一次。

模板支持两个占位符：`$sender`（撤回者）与 `$content`（被撤回内容的摘要）。
留空会自动恢复默认模板。

## v3.10：Tinker 补丁下的 dex 枚举

v3.9 日志里首次出现 `ClassLoader 相同=false`：

```
baseContext=dalvik.system.DelegateLastClassLoader[DexPathList[[zip file
  "/data/user/0/com.tencent.mm/tinker/patch-0587a26a/dex/tinker_classN.apk"]...
```

微信下发过 Tinker 热补丁后，`baseContext.classLoader` 会变成
`DelegateLastClassLoader`，**没有 `pathList` 字段**可反射，而 `sourceDir` 只指向
base.apk —— 补丁里的类枚举不到。

新增一条不依赖反射的解析：从 `ClassLoader.toString()` 里正则抠 `zip file "..."`
路径（已用真实日志验证能拿到 tinker patch 路径），并额外扫
`/data/user/0/<pkg>/tinker/patch-*/dex/*.apk` 作为再兜底。

另外：「写死候选全部未命中」在 8.0.78 上**必然发生**（类名被混淆），走 dex 枚举
是预期路径而不是异常 —— 日志级别从 WARN 降为 INFO，免得看日志的人误判。

## v3.9：v3.7 清理留下的孤立 `@Volatile`

v3.7 移除 `lastCallNoticeMs` 时用正则删整行，变量行删了但它上面的 `@Volatile`
留了下来，于是同一个属性上叠了两个 `@Volatile`：

```kotlin
@Volatile        // ← 孤立残留

@Volatile
var verbose = false
```

Kotlin 的 `@Volatile` 不可重复 → 编译报 `This annotation is not repeatable`。

已删除孤立那一行。运行时逻辑无任何改动（`verbose` 行为不变）。

## 开关状态与文字颜色联动

设置页每个开关的**标题文字颜色跟随开关状态**：

| 状态 | 标题 | 说明文字 |
|---|---|---|
| 开启 | `mm_text_primary`（黑） | `mm_text_secondary` |
| 关闭 | `mm_text_off`（灰） | `mm_summary_off`（更淡） |

标题/说明 TextView 在布局里没有 id，靠结构定位：Switch 的父是水平 `LinearLayout`，
第一个子元素是垂直 `LinearLayout`，里面第 1 个是标题、第 2 个是说明。
定位失败只绑开关不设色，不影响功能。

两套配色都在 `values/colors.xml` 与 `values-night/colors.xml` 里，夜间模式自动切换。

## 夜间模式

`values/` 用 Material Light，`values-night/` 用 Material 暗色，随系统夜间模式自动切换。
小米「夜间模式」/ 澎湃 OS 深色模式对应的正是 `-night` 这个资源限定符，无需重启 App。

没有用 `Theme.Material.DayNight`：它要求 API 29，而本模块 minSdk 26，
直接用会在 Android 8–9 上出问题。

## 一键结束微信后台

设置页的按钮走 root 执行 `am force-stop com.tencent.mm`，效果等同于
「从最近任务划掉」，省得每次手动清后台。

依次尝试 `su -c "am force-stop <pkg>"`、`su 0 am force-stop <pkg>`、
`su -c "killall <pkg>"`、`su 0 pkill -f <pkg>` —— Magisk / KernelSU / APatch
对这几种形式的支持程度不同，多试几种成功率更高。全部失败时会在按钮下方
显示 su 的原始输出，便于判断是**没授权**还是**命令不支持**。

## 撤回拦截：为什么聊天里不会有文字提示

这是 v2.1「撤回时留下提示」失败的根因，也是本模块**刻意不做**聊天内文字的原因。

WeKit 源码里有一句注释直接点破：

> WeChat only marks a message as revoked **AFTER overwriting its row in place**
> (destroying the content).

也就是说：「xx 撤回了一条消息」**不是新插入的一条提示**，它**就是被就地改写的那条原消息行本身**。

由此推出：**保住消息**和**显示系统提示**在微信的设计里是互斥的。

| 撤回动作 | 原消息行 | 聊天里显示 |
|---|---|---|
| 成功 | 被改写，内容丢失 | 「xx 撤回了一条消息」 |
| 被拦截 | 原样保留 | **什么都不会有** |

所以任何「既保住消息、又在聊天里显示自定义文字」的方案都不成立——
除非往微信**加密的 SQLCipher 数据库**插行。旧版 WeKit 就是这么做的
（`WeDatabaseApi` + 模板 `「$sender」尝试撤回上一条消息 (已阻止)`），
但那需要微信内部 DB API 的精确定位，写错字段可能损坏聊天记录，**本模块不做**。

参考项目的做法也印证了这点：

| 项目 | 做法 | 聊天内文字 |
|---|---|---|
| WeKit（现版） | 保留消息 + 气泡上贴红色角标 | 无（只有角标） |
| WAuxiliary `AntiRevoke1Hook` | 定位 `doRevokeMsg` 后 `resultNull()` | 无，UI 明确写「消息无撤回提示」 |

### 本模块的提示形式

「撤回时留下提示」开关控制的是**模块自己的浮层**，不是聊天内的系统消息：

```
已拦截「张三」撤回一条消息
```

撤回者名字从 sysmsg 的 `replacemsg` 里提取（兼容 `"张三" 撤回了一条消息`、
`你 撤回了一条消息`、无引号昵称三种形态）。关掉开关则显示通用的
「已拦截 1 条撤回指令（累计 N 条）」。

### 两条独立拦截路径

1. **sysmsg 路径**：把 `.sysmsg.$type` 从 `revokemsg` 置 null，微信不认识这条 sysmsg。
2. **doRevokeMsg 路径**：直接取消微信的撤回处理方法（`before` 里 `result = null`）。
   特征串 `doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s` 来自 WAuxiliary 源码，
   是经确认的真实指纹，不是猜测。

两条都挂上，任一生效消息就保得住。诊断模式下 20 秒后会分别报两条路径各自的
调用/拦截次数，可直接看出哪条在工作。

## 撤回拦截的两种模式

微信的撤回 sysmsg 里有三个关键字段：

| 字段 | 作用 |
|---|---|
| `.sysmsg.$type` | 分派到哪个处理分支，`revokemsg` 就是撤回 |
| `.sysmsg.revokemsg.newmsgid` | 要改写/删除的目标消息行 |
| `.sysmsg.revokemsg.replacemsg` | 插进聊天记录的那句提示，即「xx 撤回了一条消息」 |

**静默模式（默认）**：把 `$type` 置 null，微信不认识这条 sysmsg，撤回分支完全不执行。
消息留下，但聊天里毫无痕迹，事后分不清哪条被撤回过。

**提示模式（v2.1 曾尝试，已证明不可行，代码已移除）**：

1. 改写 `replacemsg` 为自定义文案（默认 `已拦截「$sender」撤回的消息`）；
2. 把 `msgid` / `newmsgid` 改成 0，撤回分支找不到目标行，改写/删除自然失败；
3. `$type` 保持 `revokemsg`，流程继续走完，提示照常插入。

于是既留住了消息，又留下了一句可辨识的提示。

> 提示模式依赖「微信在找不到目标消息时仍会插入 replacemsg」这一行为，
> 无法在沙盒里验证，**默认关闭**。打开后若发现消息仍被撤回，说明该版本走了别的分支，
> 关掉即可回到静默模式（消息照常保留）。

模板里的 `$sender` 会替换成撤回者名字，从原始 `replacemsg` 里提取 ——
兼容 `"张三" 撤回了一条消息`、`你 撤回了一条消息` 等形态。
模板是字符串，改动后需重启微信生效（镜像层同步了字符串，但不做热更新）。

## 已知限制

- **只支持 ARM**（abiFilters 限 arm64-v8a / armeabi-v7a）。
- **依赖 DexKit 特征串**：微信大版本更新后可能失效（见 `DexTargets.kt` 顶部）。
- 防撤回只能保留本机**已收到**的消息；不阻止对方自己的客户端显示「已撤回」。

## 构建排障

**`Failed to find package 'tools'`**：`android-actions/setup-android` 会去装一个已被 Google 移除的 `tools` 包，
新版 runner 上必然失败。本工程已去掉该 action，改为直接定位预装的 `sdkmanager` 并补装组件。

**`yes: standard output: Broken pipe`**：`yes | sdkmanager --licenses` 时 `yes` 被 SIGPIPE 杀掉（退出码 141），
在 `set -o pipefail` 下整个 step 失败。已改为有限行数的 `printf`，且允许该步失败。

## 许可证

沿用上游 WeKit 的 **GPL-3.0**：复用、修改这些 hook 逻辑并分发时，需以同等协议开源。
DexKit 为 LGPL-3.0。

仅供学习自用；使用第三方客户端修改功能存在账号风控风险，后果自负。
