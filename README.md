# 🚿 淋浴 (LinYu)

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-purple.svg)](https://developer.android.com/jetpack/compose)
[![Version](https://img.shields.io/badge/Version-v3.2.0-orange.svg)](https://github.com/yehu-imei/linyu/releases/latest)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**⬇️ [下载 linyu-v3.2.0](https://github.com/yehu-imei/linyu/releases/tag/v3.2.0)**

> Android 支持 Android 8.0+、arm64-v8a；iOS 主应用需 iOS 16+，桌面小组件需 iOS 17+
>
> **国内下载慢？用 Gitee 镜像** → [https://gitee.com/yehu-imei/linyu/releases](https://gitee.com/yehu-imei/linyu/releases) App 内的更新下载默认也走镜像

趣智校园第三方 Android 客户端，用于控制校园热水器。相比官方 App，提供更简洁的界面和更流畅的操作体验

iOS 独立客户端：[GitHub 仓库](https://github.com/yehu-imei/linyu-ios) · [Gitee 镜像](https://gitee.com/yehu-imei/linyu-ios) · [IPA 发行](https://github.com/yehu-imei/linyu-ios/releases/tag/v3.2.0)

iOS 包需自签安装，详见 [安装教程](https://github.com/yehu-imei/Guides/blob/main/Tutorial/.ipa%E7%9A%84%E5%AE%89%E8%A3%85.md)，使用桌面小组件时须独立安装 App，并保留小组件扩展和共享权限

## 📱 预览

<img src="assets/preview.png" width="900" alt="淋浴 App 界面总览" />

### 背景装扮

把喜欢的图片设成界面背景，App 会自动从图里提取主题色作为强调色；主页与使用页各存一套，互不影响

| 背景设置 | 主页 | 使用页 |
|---|---|---|
| <img src="assets/bg_settings.png" width="230" alt="背景设置"> | <img src="assets/bg_home.png" width="230" alt="主页背景"> | <img src="assets/bg_shower.png" width="230" alt="使用页背景"> |

> 使用页套用自定义背景后不再显示设备 emoji——背景图本身就是画面主体。

### 桌面小组件

在桌面直接启停热水，不用打开 App

| 2x2 · 空闲 | 2x2 · 使用中 | 2x4 · 设备控制 |
|---|---|---|
| <img src="assets/widget_2x2.png" width="170" alt="2x2 小组件 · 空闲"> | <img src="assets/widget_2x2_using.png" width="170" alt="2x2 小组件 · 使用中"> | <img src="assets/widget_2x4_home.png" width="380" alt="2x4 设备控制"> |

| 2x4 · 附近设备 | 2x4 · 账单 |
|---|---|
| <img src="assets/widget_2x4_nearby.png" width="380" alt="2x4 附近设备"> | <img src="assets/widget_2x4_bill.png" width="380" alt="2x4 账单"> |

## ✨ 功能

### 🚿 设备控制

- 🔐 **手机号登录** — 默认使用短信验证码，未注册的手机号会自动创建账号，支持切换密码登录
- 📡 **蓝牙扫描** — 「附近设备」按信号强度排序。权限按需申请，Android 12+ 不需要定位权限
- 🔌 **蓝牙水表直连** — 兼容的蓝牙表支持由手机开阀、关阀和结算。可恢复已确认的订单，遗留数据在复核后补结算
- 🎨 **统一的设备图标** — 热水器 / 洗手台用单色图标，不再受各厂商 emoji 字形影响
- 📷 **扫码绑定** — 通过 CameraX 和 ZXing 识别设备二维码，支持相册选图和手电筒。识别后打开设备详情
- 🏠 **绑定寝室** — 安装插件后可从「选择附近」选寝室，首页和桌面小组件按寝室筛选设备
- 🚿 **一键洗澡** — 选择设备即可开始，支持停止和恢复。云端开关水均复核设备状态，未确认时有提示
- ⭐ **常用设备** — 从上次使用、附近设备或设备详情收藏，常用设备可在首页排序
- ⏳ **自动关停倒计时** — 显示闲置自动关闭倒计时，关闭时弹出确认框
- 🔔 **后台用水监控** — Android 前台服务可在离开界面后继续轮询订单并处理到时关停，实际运行受系统后台限制
- 🔕 **系统通知** — 单台设备使用中的状态通知提供「结束使用」入口，详细的通知设置可在系统设置中更改
  - 在「设置 → 开关管理 → 通知」开关提醒，点说明进入系统通知设置。系统未授权时会在说明行提示
- 💰 **消费结算** — 关阀后按订单查询金额，确认后在结束通知显示消费与用水时长。金额未确认时保留待对账记录
- 🚰 **饮水机支持** — 自动识别直饮水机，按设备所属的已关联项目开关水、结算和查看消费。兼容性因学校和设备型号而异
- 🧩 **桌面小组件** — 1x1 / 2x2 / 2x4 三种尺寸，桌面直接启停热水；深色液态玻璃卡片，布局随尺寸自适应
  - **1x1 是一个按钮**：一格大小，点一下开/关；顶部小字标「空闲 / 使用中 / 占用中」，操作中显示转圈
  - 2x4 带侧边导航，可切换「设备控制 / 附近设备 / 账单」。卸载附近设备插件后隐藏对应页
  - 操作状态在桌面上**全局同步**：点任一个组件，所有淋浴组件同时显示「正在开启…」并转圈
  - 点「上次消费」卡片进 App 账单页；使用中点计时卡片直接关阀
  - 附近设备「选用」直接在桌面切换控制设备，不用打开 App

### 💰 钱包与账单

- 💳 **余额切换** — 点击账单页标题切换一卡通与趣智校园余额，两种真实余额都不可用时可手填初始余额
- 💰 **支付宝充值入口** — 在趣智校园余额视图打开支付宝的趣智校园小程序，返回 App 后刷新余额
- 🧾 **残留账单补扣** — 12 点后结束用水或余额不足导致没扣成的账单，可以在账单上手动「请求代扣」
- 📋 **账单查询** — 合并近三个自然月的关联项目消费，支持详情和近 7 天 / 本月趋势

### 👤 账号

- 🪪 **个人信息卡片** — 姓名、学号、学校自动获取，不用手填
- 📱 **更换手机号** — 验证码发到新号码，改完即时生效
- 🔑 **修改密码** — 可验证当前密码，也可使用当前绑定手机号收到的验证码
- 🎫 **使用码** — 领取 / 重新领取前可先预览，「换一个」每天 20 次，确定领取才真正替换
- 👥 **挤号检测** — 多设备登录自动提醒，25 秒心跳轮询，被挤下线能及时弹出提示

### 🎨 个性化

- 🖼 **背景装扮** — 主页与使用页各一套独立背景，自动提取主题色，支持透明度 / 模糊 / 亮度调节
- 🌓 **深浅主题** — 圆形揭示切换动画，设置自动保存
- 🧩 **卡片自定义** — 首页三个设备区块可排序和隐藏，「我的」与「设置」的卡片也可编辑
- 💡 **屏幕常亮** — 默认关闭，开启后只在前台显示使用页且设备使用中时生效
- 📶 **断网提示** — 网络异常时友好提示

### 🔧 其他

- 🧩 **插件管理** — 可在线安装、更新、排序和删除，也可导入本地插件清单。详见 [安卓功能管理](docs/ANDROID_FEATURE_MANAGEMENT.md)
- 📜 **运行日志** — 记录运行和异常信息，对手机号及常见凭证字段脱敏，可一键分享导出
- 🔄 **应用内更新** — 自动比对 GitHub Release，更新日志读取独立的 [精简文档](docs/APP_CHANGELOG.md)，并可直接在应用内下载安装
  - **下载默认走 Gitee 镜像**，更新卡片里可切回 GitHub
  - 更新日志默认只列最近 3 个版本，点「展开全部」看其余
- 🔐 **加密存储** — 登录凭证加密存储（EncryptedSharedPreferences）

## 📖 文档

| 文档 | 说明 |
|---|---|
| [PROJECT.md](PROJECT.md) | 完整项目文档：技术架构、项目结构、构建方法、功能清单 |
| [CHANGELOG.md](CHANGELOG.md) | 版本更新日志 |
| [SECURITY.md](SECURITY.md) | 安全说明：如何验证正版、我们的安全设计、已知暴露面 |
| [API-qzxy.md](API-qzxy.md) | 趣智校园 API 逆向工程完整参考 |
| [开发者指南.md](开发者指南.md) | 面向第三方开发者的开发指南 |
| [安卓功能管理](docs/ANDROID_FEATURE_MANAGEMENT.md) | 设置卡片、通知渠道和插件使用 |
| [插件系统 v2](docs/插件系统-v2.md) | 清单格式、版本兼容和双源校验 |

> 欢迎提 Issue 或 PR 帮助改进！

## 🙏 感谢

感谢每一位为本项目做出贡献的朋友，是你们让「淋浴」从想法一步步变成今天的样子：

| 贡献者 | 贡献内容 |
|---|---|
| [@Cainite07](https://github.com/Cainite07) | 短信登录的实现 |
| [@SubmergeDQ](https://github.com/SubmergeDQ) | 蓝牙阀控制的实现 |
| [@XiaoLoad](https://github.com/XiaoLoad) | 项目优化的宝贵建议 |
| [@Light-dark-max](https://github.com/Light-dark-max) | 联网吹风机接口文档与真机验证 |
| 各位同学们 | 软件的测试与体验反馈 |

> 每一段代码、每一条建议、每一次测试，都在让「淋浴」变得更好！谢谢你们 🙌

## 📄 许可证

[MIT License](LICENSE)

## ⚖️ 免责声明

本项目仅供学习和研究用途。使用者应自行遵守趣智校园及相关服务的使用条款。开发者不对因使用本项目产生的任何后果承担责任。
