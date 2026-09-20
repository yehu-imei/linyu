# 淋浴 (LinYu) — 项目文档

## 项目概述

**淋浴 (LinYu)** 是一款趣智校园第三方 Android 客户端，专注于校园热水器控制功能。相比官方 App，淋浴提供了更简洁的界面和更流畅的操作体验。

| 项 | 值 |
|---|---|
| 应用名称 | 淋浴 |
| 包名 | `com.hualala.linyu` |
| 版本 | v3.0.4 |
| 技术栈 | Kotlin + Jetpack Compose + Material 3 |
| 最低 Android 版本 | Android 8.0 (API 26) |
| 目标 Android 版本 | Android 16 (API 36) |
| APK 体积 | 12.6 MB（含 ML Kit 条码识别 native 库） |
| 支持的 ABI | 仅 `arm64-v8a` |
| 后端 API | 趣智校园 `v3-api.china-qzxy.cn` |
| 适用范围 | 使用趣智校园系统的学校（学校名称自动获取） |

---

## 功能清单

### 核心功能

| 功能 | 说明 |
|---|---|
| 密码登录/登出 | 手机号 + 密码登录，MD5 加密，loginCode 加密持久化，挤号检测 |
| 短信验证码登录 | v2.1.0 通用化：`secret = MD5(前3位+后4位+"klcx")` 由手机号推导，任何手机号可用 |
| 蓝牙扫描设备 | BLE 低功耗蓝牙扫描附近设备，按信号强度显示（强/中/弱） |
| 扫码绑定设备 | 扫描设备二维码，直接弹出设备详情（无需蓝牙） |
| 绑定寝室 | 在「选择附近」里选一间寝室（**没有手工输入**）。寝室键从设备名「掐头去尾」得到，同一间寝室的热水器和洗手台收敛成同一条。首页**和桌面小组件**三处都按它筛 |
| 开始洗澡 | 调用 downRate API 开启设备，并轮询确认开阀成功 |
| 停止洗澡 | 调用 closeOrder API 关闭设备，确认关闭成功，失败有提示 |
| 洗澡中界面 | 全屏沉浸式界面，显示计时器、预扣金额、设备位置 |
| 自动关停倒计时 | 解析 autoDisConTime 显示闲置自动关闭倒计时 |
| 自动关停确认弹窗 | 设备自动关闭时弹确认框（设备名 + 时长 + 消费金额） |
| 消费结算 | 关阀后直接调 `/order/consumeOrder/result/query` 问这一单结算了多少钱（约 1~2 秒），通知先显示「结算中」再原地更新成金额。⚠️ 该接口金额单位是**厘** |
| MQTT 实时推送 | 连接 MQTT 服务器接收实时消费金额更新 |
| 使用码启动检测 | 通过物理键盘使用码启动设备后，刷新可自动发现使用中的设备 |
| 多设备支持 | 支持同时管理多个活跃设备订单 |
| 上次使用设备 | 记住上次使用的设备，一键快速开始 |
| 饮水机支持 | 按 `bigTypeId == 5` 识别直饮水机，绿主题 + ❄️/♨️ 图标 + 名称精简（未实机验证） |
| 桌面小组件 | 1x1 / 2x2 / 2x4 三种尺寸，桌面直接启停热水；深色液态玻璃卡片，计时由 `Chronometer` 驱动，App 不在也能走秒 |
| 主动挤号检测 | 25 秒心跳轮询，被挤下线能及时弹提示（跟随 Activity 生命周期，退后台自动停） |
| 后台用水监控 | 前台服务 `ShowerWatchService`：超时自动关停与订单轮询脱离界面，退出 App、划掉最近任务都不影响 |
| 系统通知 | 用水期间常驻状态条（带「结束用水」按钮），结束 / 超时 / 设备被占用各有一条；总开关 + 三个细分开关 |
| 个人信息卡片 | 姓名、学号、学校自动获取（三个接口互为兜底）；「账号管理」折叠区可换手机号、改密码 |
| 更换手机号 | 验证码发到**新**号（`typeId=5`），改完即时生效 |
| 修改密码 | 密码方式走 `/user/password/update`；**没设过密码 / 忘了密码**可走 `/user/password/forget` 用短信验证码重置 |

### 钱包与账单

| 功能 | 说明 |
|---|---|
| 一卡通真实余额 | 读 `/settlement/campus/userInfo` 显示校园卡实际余额；未签约免密支付时回退到「初始余额 − 账单消费」的本地估算并在界面注明 |
| 余额估算 | 手动填写初始余额，根据账单自动扣减（真实余额拿不到时的兜底） |
| 请求代扣 | 残留账单（12 点后结束、余额不足导致没扣成）可在账单上手动补扣，走 `/order/thirdTrade/single/json/pay` |
| 账单查询 | 查看当月最近 20 条消费记录 |
| 账单详情 | 点击账单查看设备名、设备类型、消费金额、消费时间、订单号 |
| 下拉刷新 | 主页和钱包页面均支持下拉刷新 |

### 使用码

| 功能 | 说明 |
|---|---|
| 使用码显示 | 显示当前使用码，后三位蓝色高亮（与手机号后三位相同）；没领过时显示「尚未领取」而不是一直「加载中」 |
| 使用码领取 | 预览式领取：`generate` 只出候选码，`set` 才生效，所以**取消是零成本的**；「换一个」每天 20 次，换出的码有 3 分钟领取时限 |
| 使用码开关 | 远程开启/关闭使用码功能 |

### 用户体验

| 功能 | 说明 |
|---|---|
| 深浅主题 | 手动切换浅色/深色模式，圆形揭示过渡动画，设置持久化保存 |
| 悬浮胶囊导航栏 | 弹簧滑块跟随选中项，点击无水波纹（仅滑块滑动反馈） |
| 页面切换动画 | 三大主页之间左右视差滑移 + 淡入淡出弹簧动画 |
| 液态玻璃卡片 | 半透明底色 + 微光描边 + 零阴影 |
| 背景装扮 | 主页 / 使用页各一套独立背景，自动提取主题色，透明度 / 模糊 / 亮度可调 |
| 卡片自定义 | 「我的」页卡片可上下排序、隐藏显示，状态持久化 |
| 应用内更新 | 读取 GitHub Release 比对版本、折叠展示更新日志，并可在应用内下载安装。下载默认走 Gitee 镜像（可用开关切回 GitHub）；日志默认只列最近 3 个版本 |
| 内置运行日志 | 环形缓冲 + 文件滚动 + 敏感信息脱敏 + 崩溃捕获 + 一键导出 |
| 扫码手电筒 | 扫码界面提供手电筒，开启后变暖黄 + 光晕 + 填充图标 |
| 扫码相册识别 | 从相册选图识别二维码（`PickVisualMedia`，不需要存储权限） |
| 加密存储 | 登录凭证用 EncryptedSharedPreferences 加密存储 |
| 自动填充 | 登录输入框标注自动填充身份，手机密码管理器可保存 / 回填账号密码 |
| 扫描权限按需申请 | 不再登录后自动弹窗；Android 12+ 用 `neverForLocation` 免掉定位权限 |
| 学校名称自动获取 | 读 `/project/info/triple` 的 `projectName`，不用手填 |
| 网络异常提示 | 断网时显示友好提示（自定义 Toast，带应用图标） |
| 挤号检测 | 在其他设备登录同一账号时弹出强制下线提示（25 秒心跳轮询，主动发现） |
| 屏幕旋转 | 使用 rememberSaveable 保持登录状态和当前页面 |
| 退出确认 | 洗澡中退出登录时弹出警告提示 |

---

## 文件清单与项目结构

```
app/src/main/
├── AndroidManifest.xml                    # 应用清单，权限声明
├── java/com/hualala/linyu/
│   ├── MainActivity.kt                    # 主 Activity，导航、弹窗、主题管理
│   ├── QrScanActivity.kt                  # 扫码界面（CameraX + ML Kit，支持相册选图 / 手电筒）
│   │
│   ├── api/                               # 网络层
│   │   ├── QzxyService.kt                 # Retrofit 接口定义（QzxyService，含使用码 / 一卡通 / 账号 / 代扣）
│   │   ├── NetworkModule.kt               # OkHttp + Retrofit 单例，认证拦截器
│   │   ├── SafeApi.kt                     # 扩展函数，手动 JSON 解析（绕开 R8 泛型问题）
│   │   └── GithubApi.kt                   # GitHub Release / 仓库信息（更新检测）
│   │
│   ├── data/                              # 数据层
│   │   ├── AuthRepository.kt              # 登录认证逻辑（密码 / 短信）
│   │   ├── ShowerController.kt            # 开阀 / 关阀 / 结算共享层（App 与小组件共用）
│   │   ├── ShowerEvents.kt                # 服务 → 界面的进程内事件（无 replay，App 不在就丢）
│   │   └── BalanceEstimator.kt            # 余额口径（真实值优先，拿不到才本地估算，三处共用）
│   │
│   ├── model/                             # 数据模型
│   │   ├── LoginModels.kt                 # BaseResponse<T>、LoginData、UserAccount、
│   │   │                                  # WalletData、OrderStatus、BillItem、BillDTO、
│   │   │                                  # UseCodeData、BillDetail、DownRateResult、
│   │   │                                  # CloseOrderResult（含账单设备类型判定）
│   │   ├── DeviceModels.kt                # DeviceInfo、NearbyDevice（含饮水机识别、
│   │   │                                  # 设备名格式化、类型 emoji / 颜色）
│   │   ├── WidgetCache.kt                 # 小组件离线快照（附近设备 / 账单）
│   │   ├── ActiveOrder.kt                 # 活跃订单模型
│   │   └── MqttModels.kt                  # MQTT 消息模型
│   │
│   ├── service/                           # 前台服务
│   │   └── ShowerWatchService.kt           # 用水监控（超时自动关停 / 订单轮询，脱离界面）
│   │
│   ├── ui/                                # UI 层
│   │   ├── LoginScreen.kt                 # 登录页面（密码 / 短信双模式）
│   │   ├── LoginViewModel.kt              # 登录 ViewModel（网络异常友好提示）
│   │   ├── MainScreen.kt                  # 主页（设备列表、扫码、寝室筛选、余额显示）
│   │   ├── MainViewModel.kt               # 主 ViewModel（蓝牙、MQTT、洗澡控制、开阀/
│   │   │                                  # 关阀确认、消费结算、挤号检测）
│   │   ├── ShowerScreen.kt                # 洗澡中界面（计时器、自动关停倒计时）
│   │   ├── WalletScreen.kt                # 钱包页面（真实余额、账单列表、请求代扣、下拉刷新）
│   │   ├── UserScreen.kt                  # 我的页面（可排序卡片 + 进程级缓存）
│   │   ├── FloatingPillNavBar.kt          # 悬浮胶囊导航栏（弹簧滑块，点击无波纹）
│   │   ├── AppBackgroundLayer.kt          # 自定义背景渲染层（透明度/模糊/亮度）
│   │   ├── CustomBackgroundScreen.kt      # 背景装扮设置页（主页 / 使用页切换）
│   │   ├── LogViewerDialog.kt             # 内置日志查看器
│   │   ├── TailEllipsisText.kt            # 单行文本，放不下先缩字号、再省**开头**（保留结尾，设备名辨识度在房号）
│   │   ├── DeviceDetailDialog.kt          # 设备详情弹窗（SN、MAC、预扣金额、状态）
│   │   ├── LinYuToast.kt                  # 自定义 Toast 组件（应用图标 + 深色背景）
│   │   └── theme/
│   │       ├── Theme.kt                   # 深浅主题配色方案（液态玻璃卡片）
│   │       └── CircularRevealTheme.kt     # 圆形揭示主题切换容器
│   │
│   ├── widget/                            # 桌面小组件
│   │   ├── LinYuWidgetProvider.kt         # Provider 基类（1x1 / 2x2 / 2x4 共用）+ 状态推送
│   │   └── WidgetRenderer.kt              # 状态推断 + RemoteViews 渲染（无反射调用）
│   │
│   └── utils/                             # 工具层
│       ├── PrefsHelper.kt                 # 加密存储（EncryptedSharedPreferences，认证、
│       │                                  # 设备、余额、主题、寝室绑定、倒计时、背景）
│       ├── MqttManager.kt                 # MQTT 连接管理（Paho 客户端）
│       ├── BluetoothScanner.kt            # BLE 蓝牙扫描（过滤 KLCXKJ-Water 设备）
│       ├── MD5Utils.kt                    # 密码加密（MD5 取后 10 位）
│       ├── SignUtils.kt                   # 短信验证码 secret 计算（按手机号推导）
│       ├── Notifier.kt                    # 系统通知（三条渠道：用水状态 / 静默 / 事件）
│       ├── AppLogger.kt                   # 运行日志（脱敏 / 滚动 / 崩溃捕获）
│       ├── BackgroundManager.kt           # 背景图存取（主页 / 使用页两套配置）
│       ├── BackgroundState.kt             # 背景配置状态（Compose State）
│       ├── ScanPermission.kt              # 蓝牙扫描权限（按系统版本分流）
│       └── ApkUpdater.kt                  # 更新包下载 + 调起系统安装器
│
└── res/
    ├── drawable/
    │   ├── app_logo.png                   # 应用 logo（Toast 图标）
    │   ├── ic_flashlight.xml              # 扫码手电筒图标
    │   ├── widget_glass.xml              # 小组件玻璃卡底（深色中性玻璃 + 高光 + 轮廓）
    │   ├── widget_inset.xml              # 内嵌玻璃槽（上次消费 / 计时）
    │   ├── widget_badge_*.xml            # 状态徽章（空闲 / 使用中）
    │   ├── widget_btn_*.xml              # 按钮底（主操作 / 停止）
    │   ├── widget_nav_active.xml         # 2x4 侧边栏选中底
    │   ├── widget_avatar.xml             # 设备头像圆底
    │   └── ic_widget_*.xml               # 小组件矢量图标
    ├── layout/
    │   ├── widget_linyu_1x1.xml          # 小组件布局（1x1，整块一个开关，四态叠放切 visibility）
    │   ├── widget_linyu_2x2.xml          # 小组件布局（2x2，任何尺寸都用这套，靠 weight 自适应）
    │   └── widget_linyu_2x4.xml          # 小组件布局（2x4，含三页）
    ├── drawable-nodpi/
    │   ├── widget_preview_1x1.png        # 组件选择器预览图（不随屏幕密度缩放）
    │   ├── widget_preview_2x2.png
    │   └── widget_preview_2x4.png
    ├── xml/
    │   ├── network_security_config.xml    # 网络安全配置（仅允许 MQTT 明文）
    │   ├── file_paths.xml                 # 日志导出 FileProvider 路径
    │   ├── widget_info_1x1.xml            # 小组件配置（1x1）
    │   ├── widget_info_2x2.xml            # 小组件配置（2x2）
    │   ├── widget_info_2x4.xml            # 小组件配置（2x4）
    │   ├── backup_rules.xml               # 备份规则
    │   └── data_extraction_rules.xml      # 数据提取规则
    ├── values/
    │   ├── strings.xml                    # 字符串资源（app_name = 淋浴）
    │   ├── colors.xml                     # 颜色资源
    │   └── themes.xml                     # 浅色主题（Material Components）
    ├── values-night/
    │   └── themes.xml                     # 深色主题
    └── mipmap-*/                          # 应用图标
```

---

## 技术架构

### 架构模式

项目采用简化的 MVVM 架构：

```
┌─────────────────────────────────────────┐
│  UI 层 (Compose)                        │
│  LoginScreen / MainScreen / ShowerScreen│
│  WalletScreen / UserScreen              │
│  FloatingPillNavBar / AppBackgroundLayer│
│  （+ QrScanActivity，扫码页，非 Compose）│
├─────────────────────────────────────────┤
│  主题层                                 │
│  Theme (AppColors 组合局部)             │
│  CircularRevealThemeHost (圆形揭示过渡) │
├─────────────────────────────────────────┤
│  ViewModel 层                           │
│  LoginViewModel / MainViewModel         │
├─────────────────────────────────────────┤
│  服务层（脱离界面）                     │
│  ShowerWatchService 前台服务：超时关停  │
│                      + 订单轮询         │
│  ShowerEvents 服务 → 界面的进程内事件   │
├─────────────────────────────────────────┤
│  数据层                                 │
│  AuthRepository / NetworkModule         │
│  QzxyService / SafeApi / GithubApi      │
│  ShowerController 开阀/关阀/结算（App   │
│                    与小组件共用一份）   │
│  BalanceEstimator 余额口径（三处共用）  │
│  PrefsHelper / MqttManager              │
│  BluetoothScanner / BackgroundManager   │
│  Notifier 系统通知 / AppLogger          │
├─────────────────────────────────────────┤
│  模型层                                 │
│  BaseResponse / LoginData / DeviceInfo  │
│  BillItem / UseCodeData / UnpaidBill    │
│  AccountInfo / CampusUserInfo / Project │
│  ActiveOrder / MqttOrderMsg / 缓存快照  │
└─────────────────────────────────────────┘

> 架构图只列主要的。UI 层还有 `QrScanActivity`（唯一非 Compose 的页面——扫码）、
> `DeviceGlyph`（设备图标，emoji 与图标的渲染层映射）、`DeductConfirmDialog`（代扣确认，
> 两处入口共用一份文案）、`TailEllipsisText`、`LogViewerDialog` 等。
```

### 主题与背景实现要点

- 配色通过 `CompositionLocal`（`LocalAppColors`）向下传递，主题切换时走**重组**而非重建，因此不会丢状态
- 强调色可被自定义背景提取出的主题色覆盖
- 背景配置分 `home` / `shower` 两个 scope 独立存储，`AppBackgroundLayer` 按当前是否洗澡中选取对应 scope 渲染
- 状态栏透明度跟随背景启用状态自动切换

### 桌面小组件实现要点

- 小组件**不持有状态**，每次渲染都从 `PrefsHelper` 现读现算，因此不需要与 App 做状态同步
- 计时用 `RemoteViews.setChronometer()`：`Chronometer` 由启动器进程驱动，App 未运行也能实时走秒
  - 注意 `startedAt` 存的是 `System.currentTimeMillis()`（挂钟），而 `Chronometer` 要的是
    `SystemClock.elapsedRealtime()`（开机以来）基准，两者原点不同，必须换算后再传入
- 全程只用 RemoteViews 一等公民 API（`setTextViewText` / `setViewVisibility` / `setChronometer` /
  `setOnClickPendingIntent`），**不用** `setInt(id, "setXxx", ...)` 反射写法——
  框架对反射方法有 `@RemotableViewMethod` 白名单，不通过会让整个小组件渲染失败
- 动画类的东西一律没有（RemoteViews 跑不了）。**但 `ProgressBar` 的不确定态是系统进程画的**，
  所以「正在开启…」的转圈是挂一个 `ProgressBar`，不是自己画动画
- **三种尺寸**（v3.0.1 加 1x1）：
  - `1x1` 整块就是一个开关，三种状态叠在同一位置切 visibility
  - `2x2` **只有一套布局**，被拉宽拉高都靠 `layout_weight` 自适应。
    v2.2.1 曾按宽高比切成「按钮在右侧」的横向版，但横向版里卡片固定 96dp、圆球固定 58dp，
    都不跟尺寸缩放，拉大只会多出空白；判据 `minWidth > minHeight` 又过于灵敏，
    稍微一拉就跳过去，v2.2.2 已整体移除
  - `2x4` 带侧边导航，三个页面
- **1x1 的两处特殊处理**：
  - 「空闲」那块是三种情况共用的（真·空闲 / 未登录 / 没选过设备），只有顶部那行小字不同
  - 布局根节点加了内边距对齐应用图标的视觉大小。`minWidth/minHeight` 不是「组件多大」
    而是「至少需要多大」，桌面据此给格子后会把组件**拉伸填满**，不加内边距玻璃块会顶到格子边缘
- `DisabledReason.isNotice` 区分「过程」和「结果」：**过程**才转圈、才禁点击；
  失败/占用这类**结果**照常可点（用户多半想重试）
- ⚠️ **PendingIntent 的目标组件必须是 Manifest 里注册过的 receiver**。
  基类 `LinYuWidgetProvider` 没有注册，把广播发给它会**被系统静默丢弃**（无异常、无日志），
  表现就是「点按钮毫无反应」。渲染时需反查该 widget id 属于哪个尺寸的 receiver——
  **每加一个尺寸都要在 `forEachWidget` 的登记表里加一项**，否则那个尺寸收不到重绘
- 设备类型**在小组件里仍是 emoji**（账单行 / 附近设备行的圆头像）。App 界面用的是图标，
  两边的取舍见 `design/device-icons/README.md`
- 通知：横幅类走 `linyu_alert` 渠道（`IMPORTANCE_HIGH`）。⚠️ 渠道 importance 创建后改不了，
  所以「关掉横幅」是另建一条 DEFAULT 渠道二选一，不是改原来那条
- 开阀 / 关阀逻辑与 App 共用 `data/ShowerController.kt`；小组件侧只额外限制确认轮询预算（6 秒），
  超时返回「状态未知」并提供手动刷新，而不是谎报成功或失败
- 状态推送：App 内进入 / 退出洗澡、自动关停时调用 `LinYuWidget.refreshAll(context)`

### 关键依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| Jetpack Compose BOM | 2024.02.01 | 声明式 UI 框架 |
| Material 3 | BOM 管理 | Material Design 3 组件 |
| Material | BOM 管理 | pullRefresh 下拉刷新组件 |
| Retrofit | 2.9.0 | HTTP 客户端 |
| Gson | Retrofit 内置 | JSON 序列化/反序列化 |
| OkHttp Logging | 4.12.0 | HTTP 日志（仅 Debug） |
| Eclipse Paho MQTT | 1.2.5 / 1.1.1 | MQTT 客户端 |
| Jetpack Security Crypto | 1.1.0-alpha06 | EncryptedSharedPreferences 加密存储 |
| CameraX | 1.4.2 | 相机预览（扫码） |
| ML Kit Barcode | 17.3.0 | 二维码识别 |
| AndroidX Core | — | FileProvider（日志导出）、WindowCompat（边到边） |
| AppWidget / RemoteViews | 平台内置 | 桌面小组件（无额外依赖） |

### R8 混淆兼容方案

R8 full mode 会擦除 Kotlin suspend 函数的泛型签名，导致 Gson 无法解析 `BaseResponse<T>` 的类型参数。

**解决方案**：
1. Retrofit 接口返回 `Call<ResponseBody>`（非 suspend，非泛型）
2. `SafeApi.kt` 中定义扩展函数，使用 `suspendCancellableCoroutine` 桥接回调
3. 手动用 `JsonParser` 解析 JSON，用 `Class<T>` 反序列化 data 字段
4. 完全不依赖 Gson 的泛型反射，R8 无法破坏

同样的思路也用在 `GithubApi.kt` 上：更新检测不引入 GitHub SDK，直接解析 GitHub REST 返回的 JSON，任何一步失败都返回 null / 空列表，绝不阻塞主流程（国内网络访问 GitHub 常失败）。

### 网络安全配置

- HTTPS API（`v3-api.china-qzxy.cn`）：正常 HTTPS
- MQTT（`tcp://47.107.37.60:1883`）：明文 TCP（趣智校园原始协议，无法修改）
- 其他所有明文流量：禁止

---

## 构建说明

### 环境要求

- Android Studio（推荐自带 JBR/JDK 21）
- JDK 17+（本项目实测使用 Android Studio 自带 JBR/JDK 21 构建）
- Android SDK 36
- Gradle 9.4.1

### 签名配置

**开源版本不包含签名密钥。** 你需要自行生成签名密钥：

```bash
keytool -genkey -v -keystore your-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias your-alias
```

然后在项目根目录创建 `local.properties`：

```properties
KEYSTORE_FILE=../your-key.jks
KEYSTORE_PASSWORD=你的密钥库密码
KEY_ALIAS=你的别名
KEY_PASSWORD=你的密钥密码
```

### 构建命令

```bash
# Debug 构建（用于开发测试）
./gradlew assembleDebug

# Release 构建（R8 混淆 + 资源压缩 + 签名）
./gradlew assembleRelease

# 若因网络无法下载 lint 依赖而失败，可跳过 lint 检查：
./gradlew assembleRelease -x lintVitalRelease
```

### APK 输出位置

```
app/build/outputs/apk/
├── debug/
│   └── app-debug.apk          # Debug 版本（未签名，未混淆）
└── release/
    └── app-release.apk        # Release 版本（已签名，已混淆，已压缩）
```

### Release 构建配置

`app/build.gradle.kts` 中的 Release 配置：

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true        // 启用 R8 代码混淆
        isShrinkResources = true      // 移除未使用的资源
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
        signingConfig = signingConfigs.getByName("release")
    }
}
```

---

## 已知限制

| 限制 | 说明 |
|---|---|
| 各校部署差异 | 已在多所学校被实际使用。各校接入的设备类型、BLE 广播名、MQTT 地址可能不同：多数学校装发行版即可，少数需自行改代码 |
| projectId | **不需要手动配置**：登录响应里的 `userAccount.projectId` 会被自动保存并沿用到后续请求，代码中没有硬编码任何学校的 projectId |
| 挤号检测有最多 25 秒延迟 | 靠心跳轮询实现（v2.2.0 前是完全发现不了） |
| 饮水机未实机验证 | 识别与 UI 已实现，但作者所在学校无直饮水机，实际控制流程未验证 |
| 小组件不显示实时消费 | 小组件不连 MQTT，使用中只显示预扣金额 |
| Android 12 以下仍需定位权限 | 系统对蓝牙发现的硬性规定，无法绕过 |
| 自动填充依赖厂商 ROM | 不同厂商密码管理器行为差异较大，未在多机型验证 |
| 实时扣费 | MQTT 仅在订单结束时推送消费金额，洗澡中无实时扣费（官方 App 也是如此） |
| 结算延迟 | **已经很快了**：不再翻账单列表，直接问 `/order/consumeOrder/result/query`，实测关阀后第一次查就有结果（约 1~2 秒）。只有这个接口整个失败时才回退到账单列表——那条路仍然慢且容易拿不到 |
| 一卡通余额需已签约 | 走 `/settlement/campus/userInfo` 能直接拿到真实余额（趣智校园代理了易校园，**不用破签名**）。**没签约免密支付**时服务端不给 `amount`，只能回退本地估算 |
| MQTT 明文 | 趣智校园 MQTT 服务器不支持 TLS，通信内容未加密 |
| 密码安全 | 趣智校园使用 MD5 取后 10 位作为密码，安全性较低（官方协议限制） |
| 学校适配 | 多数学校装发行版即可（projectId 自动下发）。部署方式特殊的才要改 **BLE 过滤名 / MQTT 地址**，见下方「不同学校的适配」 |
| 深色模式 | 登录页和洗澡页为硬编码颜色切换，非完全跟随系统；扫码页固定深色（相机画面上深浅模式没有意义）|
| 1x1 图标对齐 | 占用仍是**一格**（和应用图标一样），只是靠内边距让视觉大小接近。各家桌面的格子尺寸和图标内边距都不同，做不到像素级一致，6dp 是折中值 |
| 小组件仍是 emoji | 账单行 / 附近设备行的设备头像用的是 emoji，和 App 内的图标不一致（那两个列表里可能有饮水机，换了会图标与 emoji 混排）|
| 多语言 | 仅支持中文 |
| 关阀结果无法完全确认 | 断网时只能知道「请求没发出去」，无法知道服务端**在断网前有没有收到**。这时按未确认处理并把本地状态放回去（显示为仍在用水）——宁可多显示一次使用中，也不谎报「已结束」|
| 「占用中」徽章可能提前消失 | 徽章的是非对错取决于「对方什么时候用完」，而那个时间客户端拿不到。目前用 **3 分钟** TTL 兜底，所以对方洗得久时徽章会先消失——点一下仍会正常提示被占用。理论上界应该是设备的 `autoDisConTime`，但那个字段的实测样本还没拿到 |

### 历史问题的修复记录

| 原问题 | 状态 |
|---|---|
| 短信登录 secret 绑定账号 | ✅ v2.1.0：secret 由手机号推导（`SignUtils`），任何手机号可用 |
| 被挤号后重登又被弹出、需登两次 | ✅ v2.2.0：旧会话在途请求会清掉新会话凭证，改为会话级作用域整体取消 |
| Android 12+ 被迫要定位权限 | ✅ v2.2.0：`neverForLocation` + 改由用户主动触发申请 |
| 退出登录后上一任计时器残留 | ✅ v2.2.0：`clear()` 按前缀删除 `startedAt_` / `autoDiscon_` |
| 切到「我的」页面卡顿 | ✅ v2.1.0：卡片顺序 / Release / 仓库信息改为进程级缓存 |
| 设备名残留「表」字 | ✅ v2.1.0：修正正则顺序，`热水表-xxx` 不再被截断 |
| 使用页退出按钮点击无响应 | ✅ v2.1.0：修正组件层级，按钮不再被上层 Column 拦截 |
| 使用页「已预扣」卡片不透明色块 | ✅ v2.1.0：补 `Color.Transparent`（Surface 默认不透明） |
| 主题切换跳回首页 / 闪烁 | ✅ v2.1.0：状态移出过渡容器 + 遮罩先绘一帧 |
| 版本号硬编码 | ✅ v2.1.0：改读 `BuildConfig.VERSION_NAME` |
| 小组件账单页余额偏高 | ✅ v2.2.3：只减了 2 笔账单，App 用 20 笔，减数偏小导致虚高 |
| 预发布被当成正式版 | ✅ v2.2.3：`fetchReleases()` 没过滤 `prerelease` |
| 「上次消费」不跟设备走 | ✅ v2.2.3：金额按 `snCode` 分开存 |
| 余额只能手动估算 | ✅ v3.0.0：接 `/settlement/campus/userInfo` 拿真实余额 |
| 退出使用页后超时关停失效 | ✅ v3.0.0：倒计时和订单轮询搬进前台服务 |
| 通知栏点「结束用水」后使用页不退出 | ✅ v3.0.0：`doFinish` 漏发结束事件 |
| 消费金额显示成上一次账单的金额 | ✅ v3.0.0：`settleAmount` 的时间过滤条件写反了 |
| 小组件开阀后没有「使用中」通知 | ✅ v3.0.0：`runCatching` 把服务启动失败吞了 |
| 换手机号验证码发到旧号 | ✅ v3.0.0：实测是发到**新**号（`typeId=5`），代码写反了 |
| 姓名拿不到 | ✅ v3.0.1：`/account/info` 依赖学校同步学籍，改为三个接口互为兜底 |
| 首次加载无余额时闪错数字 | ✅ v3.0.1：账单没到位不显示估算值 |
| 开阀失败什么都不显示 | ✅ v3.0.2：`showerError` 以前**只写不读**，现在弹窗 + 小组件 + 横幅三处联动 |
| 代扣可能重复扣款 | ✅ v3.0.2：超时/取消被当成「失败」会诱导重试；列表刷新无乱序守卫；批量没去重 |
| 重新领取使用码显示八个短横线 | ✅ v3.0.2：自动换码的条件写成「只在没码时才换」 |
| 结束通知永远显示「无消费」 | ✅ v3.0.3：`orderId`(7位) 拿去比 `orderNo`(20位)，永远不成立；时间窗又被本单自己滤掉。改问 `/order/consumeOrder/result/query` |
| 消费金额放大 1000 倍 | ✅ v3.0.3：那个接口的金额单位是**厘**、账单列表是**元**，同名字段差 1000 倍 |
| 零消费要等 8 秒 | ✅ v3.0.3：5 轮 + 3 轮固定重试纯白等，实测第 1 轮就是终值。改成 2 轮 + 1 轮 |
| 组件的「选择附近」列出的名字匹配不上设备 | ✅ v3.0.3：末段是数字就**凭空补「房」**，真实设备名里没这个字。改成从原始名「掐头去尾」取寝室键（issue #6）|
| 小组件完全没读绑定寝室 | ✅ v3.0.3：主屏显示的是「上次开过阀的设备」，开阀也不看绑定。三处都加了寝室闸门 |
| 绑定寝室后小组件一直显示「请先选择设备」 | ✅ v3.0.3：闸门拿 `lastDeviceName`（**格式化过**、楼层被去掉）算键，和原始名算出来的对不上。新增 `lastDeviceRawName` |
| 首页取消绑定后要等一会儿才刷新 | ✅ v3.0.3：`PrefsHelper.boundRoom` 是普通 pref 不是 Compose 状态，改它不触发重组。改成 ViewModel 里的 State |
| `sync-all.sh` 上传 APK 静默跳过 | ✅ v3.0.3：第 3 步 `cd` 换了工作目录，第 5 步相对路径失效，打印「没给 APK」就过去了。改成先转绝对路径 |
| 关阀没成功却报「已结束」 | ✅ v3.0.4：确认循环无论结果如何都 `clearDeviceState` + 返回 `Closed`；且它查的 `closeOrder/result/query` 实测**恒返回 `data:null`**，那段"确认"实际只验证了 HTTP 成功。新增 `CloseOutcome.Unconfirmed` |
| 关阀未确认时本地状态对不上 | ✅ v3.0.4：停止流程先清本地状态（为了点完即时反馈），失败时不回滚 → 界面显示空闲、设备还在跑，且 `startedAt` 已被清、计时从 0 重来。新增 `restoreActiveOrder` + `ShowerEvents.ordersRestored` |
| MQTT 把 orderNo 写成设备序列号 | ✅ v3.0.4：`showerSnCode?.let { updateOrderNo(it, it) }` 内层 `it` 遮蔽外层，两个参数都成了 snCode |
| 小组件「占用中」永久残留 | ✅ v3.0.4：`occupiedSnCode` 全项目只有小组件自己读写、且无过期机制。App 侧清除 + 3 分钟 TTL |
| 断网点小组件开启无任何反馈 | ✅ v3.0.4：`openValve` 第一步就发请求且自身无 try/catch，异常被冒到接收器吞掉，卡片闪一下就没。改为「网络异常」+ 横幅 |
| 「选用」失败文案笼统 | ✅ v3.0.4：`pickDevice` 返回 `Boolean`，「设备不存在」和「网络不通」说不出区别。改为三态 `PickResult` |
| 更新包只校验字节长度 | ✅ v3.0.4：补包名、签名证书、可选 SHA-256；不通过则**阻止安装** |
| 加密存储静默降级为同名明文 | ✅ v3.0.4：回退文件与加密文件同名，加密恢复后会读到格式不符的同名文件 → 「登录态莫名丢失」。改用独立文件 `linyu_prefs_plain` |
| 备份规则是未改动的 Android 模板 | ✅ v3.0.4：内容全被注释，等于没排除任何东西，凭证会进云备份/设备迁移 |
| 蓝牙扫描失败后重复回调 | ✅ v3.0.4：超时任务没撤销，失败后还会再回调一次「完成」，上层把空列表当有效快照 |
| 日志共享非线程安全的日期格式 | ✅ v3.0.4：`SimpleDateFormat` 共享实例 + 文件读写无锁。改用 `java.time` 并加锁 |
| 干净环境跑不了测试任务 | ✅ v3.0.4：`signingConfigs` 在配置阶段无条件读 `local.properties`，缺文件连 `testDebugUnitTest` 都失败 |
| SECURITY.md 与实现不符 | ✅ v3.0.4：声称「Release 不包含请求/响应体日志」，实际自有拦截器照记（最多 300/400 字符，已脱敏）|

---

## 版本历史

### v3.0.4 (2026-09-20)

**质量修复版本，无新增功能。** 16 个文件，改动集中在异常路径与工程加固——正常使用下的行为与 v3.0.3 一致。

- **关阀结果不再谎报** — `closeValve` 的确认循环无论结果如何都 `clearDeviceState` + 返回 `Closed`；而且它查的 `closeOrder/result/query` 实测**恒返回 `data:null`**，那段"确认"实际只验证了 HTTP 成功。新增 `CloseOutcome.Unconfirmed`，关阀请求本身失败时如实提示。判据取「一点证据都没有」而不是「确认得很完美」——后者会让网络稍有抖动就报未确认，那种噪声会淹没真正的异常
- **未确认时回滚本地状态** — 「先清状态再关阀」的顺序保留（那是为了点完即时反馈），改为失败时 `restoreActiveOrder` 把活跃订单与 `startedAt` 一并放回。`startedAt` 是关键，少了它计时会从 0 重来。回滚发生在 `notifyFinished` 之后，App 内存副本已删设备，故补 `ShowerEvents.ordersRestored` 事件同步
- **MQTT orderNo** — 内层 `it` 遮蔽外层，`updateOrderNo(it, it)` 把 orderNo 写成了 snCode
- **小组件「占用中」** — 加 App 侧清除 + 3 分钟 TTL（原来只有小组件自己读写、且无过期，会永久残留）；断网点开启加「网络异常」提示与横幅（原来异常被吞、界面无反馈）；「选用」失败区分设备不存在/网络异常（`pickDevice` 由 `Boolean` 改三态 `PickResult`）；同一占用周期内只弹一次横幅
- **更新包校验** — 包名比对 + 签名证书 SHA-256 比对 + 可选 Release SHA-256。不通过则阻止安装。GitHub `digest` 不可用时（如 Gitee 下载）不阻止安装，但签名校验不跳过
- **认证数据** — 备份规则此前是未改动的 Android 模板（等于没排除任何内容），现排除两份 prefs 与日志；加密初始化失败的回退改用独立文件 `linyu_prefs_plain`（原与加密文件同名）
- **日志** — 共享的 `SimpleDateFormat` 改用线程安全的 `java.time`，文件截断与追加共用一把锁
- 其余：下载复用与旧版清理、蓝牙扫描失败后的双回调、Release 签名配置改为条件创建、`SECURITY.md` 如实修订

### v3.0.3 (2026-09-18)

- **消费金额终于对了** — 结束通知几乎总是「无消费」，两个 bug 叠在一起：`orderId`(7 位) 拿去比 `orderNo`(20 位)（永远不成立），时间窗 `consumeDate >= startedAt` 又把本单自己滤掉（账单记的是**下单**时刻，比开阀确认早 1~2 秒）。改问 `/order/consumeOrder/result/query`
- **金额单位是厘** — 那个接口的 `consumeMoney` 是**厘**、账单列表同名字段是**元**，差 1000 倍且**静默**错。两笔独立订单用 `dealDate` 对齐验证过
- **结算体积感** — 通知先显示「用时 X · 结算中…」，**同一个通知 id**，拿到金额后原地更新成「消费 ¥x.xx」；零消费从 8.4 秒缩短到约 1.5 秒（实测 27 次调用第 1 轮就是终值，原来 5+3 轮固定重试纯白等）
- **绑定寝室重做（issue #6）** — 关键词不再「取最后一段 + 凭空补房字」，改成从原始设备名「掐头去尾」，同一寝室的两台设备收敛成同一条、和扫到哪台无关，也不会再把隔壁楼同号房串进来
- **小组件开始认绑定了** — 以前主屏、附近设备页、开阀动作三处**都没读** `boundRoom`。现在都筛；用水中的卡片不受影响（那是唯一的停止入口），provider 的闸门只拦开阀、停止和刷新放行
- **绑定后清理寝室外的上次设备** — `lastDeviceSnCode` 是小组件主按钮的开阀依据，留着等于留一条「点一下就开别寝室的水」的路。正在用水的不清
- 手工输入关键词的输入框去掉，只能从「选择附近」选
- 界面：设备详情卡片换回 emoji（那处没底色，白图标压在浅色背景上看不见）、花洒图标下面那条水流线加长到和上面等长、提示气泡改单行头部省略
- 首屏取消绑定后要等一会儿才刷新（`PrefsHelper.boundRoom` 不是 Compose 状态，改它不触发重组）
- `sync-all.sh` 上传 APK 静默跳过（第 3 步 `cd` 换了工作目录，相对路径失效）
- `design/device-icons/svg2vector.py` 不再随仓库分发（避免语言统计出现 Python）

### v3.0.2 (2026-09-18)

- **设备图标** — 🚿 🪥 从 emoji 换成单色 vector，用在主视觉位（主界面卡片、使用页、设备详情）。数据层仍是 emoji 字符串，只在渲染时映射，老用户零迁移；小组件的列表行**不换**
- **开阀失败三处联动** — `showerError` 以前只写不读，失败时什么都不显示。现在 App 弹窗（含欠费清单 + 一键补扣）/ 小组件提示 / **横幅通知**三处都有
- **横幅提醒** — 新增 `linyu_alert` 渠道（`IMPORTANCE_HIGH`）。之前三条渠道最高才 DEFAULT，**全 App 一个横幅都没有**；渠道 importance 创建后改不了，所以用两条渠道按开关切
- **代扣的一批重复扣款隐患** — 请求超时/被取消被当成「失败」会诱导重试（可能扣两次）→ 新增 `DeductResult` 三态，「结果未知」重查服务端定夺；未支付列表加乱序守卫；批量补扣去重
- **欠费提示的误导与状态残留** — 只有服务端明确拒绝才引导补扣（断网不再引导去扣钱）；笔数金额口径统一；金额识别不了时禁用确认；登出清状态
- **使用码两个问题** — 打开弹窗就自动换一个（以前只在没码时才换，有码的看到八个短横线）；待领码只有后三位高亮（原先整串一个颜色）
- 代码收口：两套代扣确认弹窗合并、四条通知并进 `postEvent`、清掉三处死代码

### v3.0.1 (2026-09-17)

- **新增 1x1 小组件** — 一格大小，点一下开关热水。顶部小字标状态（空闲 / 使用中 / 占用中），操作中显示转圈（用系统 `ProgressBar`，因为 RemoteViews 跑不了动画）
- **姓名拿不到的根因修掉了** — `/account/info` 的姓名依赖学校同步学籍，没同步的账号返回 `null`。现在三个来源汇总：新增 `/account/card/getBindCardInfo`，并补上从 `/settlement/campus/userInfo` 取 `studentName`（原来只取了学号）
- 修复：首页「使用中的设备」名字长了换行把卡片撑变形、点「正在使用」通知回到首页而不是使用页、通知权限提示块没占满一行、关掉通知总开关后仍提示打开权限
- 详见 [CHANGELOG](CHANGELOG.md)

### v3.0.0 (2026-09-17)

2.0 以来最大的一次更新。三个"一直做不到"的限制被解掉了。

- **一卡通真实余额** — 走 `GET /settlement/campus/userInfo` 直接读校园卡账户。趣智校园代理了易校园的接口，所以不用破那套 HMAC-SHA256 native 签名。未签约免密支付时自动回退到本地估算
- **后台用水监控** — 前台服务 `ShowerWatchService`。原先超时自动关停和订单轮询写在 `MainViewModel.timerJob` 里，退出使用页或被划掉就停摆；现在跟界面彻底解耦
- **系统通知** — 常驻状态条（带「结束用水」按钮）+ 结束/超时提醒。关掉开关只是把它收进静默渠道，后台监控照常跑
- **个人信息卡片** — 姓名/学号/学校自动获取；「账号管理」折叠区里可换手机号、改密码
- **修改密码的短信验证分支** — 走 `/user/password/forget`，用验证码注册的账号（没有旧密码）终于能改密码了
- **使用码改版** — 预览式领取：`generate` 只出候选码，`set` 才生效，所以「取消」零成本
- **扫码页重写** — Compose 重做，支持相册选图识别、取景框、震动反馈、手电筒状态反馈
- 修复 14 项问题，详见 [CHANGELOG](CHANGELOG.md)

### v2.2.3 (2026-09-16)

- **更新包支持国内镜像下载**：默认走 Gitee（实测 1.9 MB/s），GitHub 约 100 KB/s。检查更新仍走 GitHub API，只有下载换源；更新卡片里有开关可切回 GitHub
- 修复：小组件账单页余额偏高（只减了 2 笔账单，App 用 20 笔）、扫描失败清空附近设备快照、预发布被当成正式版、「上次消费」不跟设备走
- 增加 Gitee 镜像仓库，两个仓库互相加了链接

### v2.2.2 (2026-09-16)

- **包体积 42.6 MB → 12.5 MB**：
  - 图标原先同一个 1254×1254 PNG 被复制了 16 份（5 密度 × 3 名字 + app_logo），占 11.6 MB；按各密度重建并改用调色板 PNG
  - 只打包 `arm64-v8a`（x86 / x86_64 的 ML Kit so 合计 11.5 MB，只有模拟器用得到）
  - 移除 `material-icons-extended`（只用到 2 个图标，R8 却残留 10660 个图标类）
  - 资源语言限定 `zh` / `en`
  - ⚠️ 由此**不再支持纯 32 位设备**
- 小组件「选用」改为在桌面后台切换控制设备，不再跳回 App 弹详情
- 修复：小组件账单页余额不跟消费变化、选用点错设备（PendingIntent requestCode 冲突）、
  余额先闪初始值再跳变、短信倒计时延迟
- 蓝牙扫描 10 秒 → 5 秒；2x2 取消横向圆球布局，任何尺寸都用同一套卡片布局；
  附近设备页区分「扫过没有」与「没扫过」；密码框加一键清空

### v2.2.1 (2026-09-15)

- **桌面小组件重做**：改成深色中性液态玻璃 + 白字的固定配色，不再按壁纸明暗切深浅
- 小组件布局从 6 套（浅深各半）精简为 **3 套**（2x2 竖向 / 2x2 横向 / 2x4）
- 开关按钮只留图标；2x4 头部整行铺满、徽章贴最右
- 附近设备页补 dB 数值与「（x 分钟前）」扫描时间
- 操作状态全局同步 + 卡片转圈；卡片点击按状态分流（空闲→账单页，使用中→关阀）
- 深链：附近设备「选用」直接弹出设备详情；账单页 → App 账单页
- 修复：按钮点击无反应、2x2 拉宽后加载失败、附近设备页崩溃、预扣显示 ¥0.00、
  两个小组件计时错相位、跨布局徽章颜色不一致
- 首页设备名 / MAC 改为头部省略且不换行（系统字体放大也不会撑破卡片）

### v2.2.0 (2026-09-14)

- **桌面小组件**（1x1 / 2x2 / 2x4，桌面直接启停热水，Chronometer 实时计时）
- **扫描权限按需申请**（Android 12+ 不再需要定位权限）
- **登录页接入系统自动填充**（保存 / 回填账号密码）
- **挤号检测改为主动**（25 秒心跳轮询）
- 抽出 `data/ShowerController.kt` 共享网络层，App 与小组件共用开阀/关阀逻辑
- 修复：被挤号后重登又被弹出（需登两次）、退出登录后计时器残留、验证码框宽度跳变、
  更新日志显示 Markdown 星号、换背景图后参数被沿用
- 背景默认参数调整为 透明度 100% / 模糊 0 / 亮度 100%
- 关于项目卡片改版（显示 Star 数与联系方式）

### v2.1.0 (2026-09-14)

- **短信验证码登录通用化**（secret 由手机号推导，任何手机号可用）
- **饮水机支持**（识别 + 绿主题 + 冷热图标 + 设备名精简）
- **内置运行日志查看器**（脱敏 + 滚动 + 崩溃捕获 + 导出）
- **背景装扮**（主页 / 使用页双套配置 + 主题色提取 + 三项参数调节）
- **UI 升级**（悬浮胶囊导航栏、圆形揭示主题切换、页面弹簧滑移、液态玻璃卡片）
- 「我的」页面卡片可排序 / 可隐藏
- 应用信息卡片自动检测 GitHub 更新 + 折叠更新日志
- 关于项目卡片显示制作者与 Star 数
- 修复：切「我的」卡顿、设备名残留「表」字、使用页退出按钮无响应、使用页色块、主题切换丢状态与闪烁

### v1.2.0 (2026-08-08)

- 开阀确认（开始洗澡时确认开阀成功）
- 自动关停倒计时与确认弹窗
- 消费金额结算（账单接口，异步获取）
- 主页扫码绑定设备 + 扫码手电筒
- 绑定寝室与设备列表筛选
- 加密存储（EncryptedSharedPreferences）
- 退出登录闪退修复
- 挤号重登触发优化

### v1.1.0

- 最低 SDK 提升至 API 26
- Kotlin 2.0.21 + AGP 8.13.2 升级

### v1.0 (2026-05-30)

- 初始版本
- 登录/登出、挤号检测
- 蓝牙扫描设备
- 开始/停止洗澡
- MQTT 实时消费推送
- 使用码显示/开关
- 多设备活跃订单管理
- 余额手动估算
- 账单查询
- 深浅主题切换
- 网络异常友好提示
- R8 混淆 + 资源压缩

---

## 免责声明

本项目仅供学习和研究用途。使用者应自行遵守趣智校园及相关服务的使用条款。
开发者不对因使用本项目产生的任何后果承担责任。
