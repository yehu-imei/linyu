# Android 3.1.0（17 / 18）本地测试记录

日期：2026-11-06

## 版本与发布边界

- 基线：master `5bdfc5171884eb50759c833eaa3ce5bd28cfb680`，已含 PR #8、#9、#11
- 第一批：仅递增 versionCode 至 17，versionName 维持 3.1.0；本地提交 `b376e4d`
- 第二批：versionCode 18，versionName 3.1.0，与 iOS 主应用和小组件的配置一致
- 不创建 Release、不打发布标签、不上传 APK；正式版本发布时间另定
- 没有修改 iOS 代码，也没有调用真实设备、账户操作或扣款接口

## 第二批改动及对照依据

| 内容 | Android 改动 | iOS 对照 |
| --- | --- | --- |
| 展开标题 | 账号管理、重新领取共用居中文字及右侧箭头，统一上下间距，无点击水波纹 | `LinYu/UserCards.swift` 的 CollapsibleHeader |
| 使用码额度 | 按账户、项目、日期保存服务端剩余次数；耗尽后不再进入生成弹窗，错误码 79 同样记录耗尽；最后一次生成的有效码仍能领取 | `LinYu/UseCodeQuotaStore.swift`、`AppDataStore.generateUseCode` |
| 使用码开关 | 单次请求保护，服务端确认后更新，失败保留原状态；旧查询不能覆盖新操作 | `AppDataStore.setUseCodeEnabled` |
| 更新日志 | 进入卡片、回前台自动刷新；手动检查强制刷新；Raw 失败使用 GitHub Contents API；有效整份文档覆盖缓存，失败保留本地 | `UserReleaseNotes.swift`、`SoftwareUpdateCard.swift` |
| 上次消费 | 遍历账单查找当前设备最新有效消费；优先完整原名，旧数据才使用显示名与类型；按设备保存时间，旧账单不覆盖新结算 | `WidgetShared/ConsumptionCachePolicy.swift` 及账单回填思路 |
| 个人信息 | 无缓存时显示四行占位；已有资料直接展示；读取失败提供重试 | `UserCards.swift` 的 AccountCardView |
| 背景重置 | 文案改为“这将移除当前页面的自定义背景” | iOS 背景重置确认 |

没有移除 Android 独有的后台监控、通知关水、小组件直接控制能力。没有把测试改动写成已发布的新版本日志。

## PR #12 暂缓合入

审查对象：`f48f484c362bfc0f6b7f1fbc88a5489f5caf8f63`，不是当前 master。

1. **洗衣机结束状态未闭环**：`MainViewModel.kt:1178` 的 `startWasherPoll` 在正常轮询中只更新状态，不判断 `looksActive == false` 并调用 `finishWasherState`。后者目前只在项目恢复路径调用；因此完成后可能保留运行卡片和持久化会话，`MainActivity` 的 `hasWasherSession` 还会阻止退出登录。需补“明确结束 / 暂时离线 / 归属变化”的区分及回归测试，不能把网络失败当结束。
2. **洗衣机可能进入热水器小组件链路**：`MainViewModel.kt:1139` 把洗衣机写入通用 `lastDeviceSnCode`，但 `LinYuWidgetProvider.kt` 的 ACTION_START 仍只区分 BLE 与 `ShowerController.openValve`，没有洗衣机模式选择分支。需按设备类型分流，并验证上次设备、小组件选用、开关入口都不会把洗衣机当水表。
3. **异步项目检查不充分**：`sessionScope` 已固定协程的 ProjectSession，回调再比较 `NetworkModule.currentProjectId()` 与请求项目时，读到的仍是固定的旧项目，并不代表当前可见项目。项目切换成功前旧会话尚未取消；部分 UI 请求也不属于 sessionScope。现有隔离测试证明凭证与存储隔离，但没有覆盖这一切换窗口。需增加独立的可见会话代次检查以及切换中、退出重登的并发回归。

以上代码未合入构建 18，也未修改、关闭或合并 GitHub 上的 PR。

## 验证

- 构建 17：85 项单元测试通过，Debug Lint 无错误，Release 构建及 APK 签名校验通过
- 构建 18：新增 22 项回归，合计 107 项通过；Debug Lint 无错误（仍有既有警告），Release 构建通过
- 原签名证书 SHA-256：`ccaf8d04aa73a3b3d04a9e0d3abba3d89f8217d411b543f4b563dd3fa2e7e41a`
- 纯逻辑与实际 PrefsHelper 持久化测试覆盖额度耗尽、跨日、账户/项目隔离、最后一个候选码、旧消费防覆盖、日志备用源、取消与缓存替换

## 真机待验证

1. 覆盖安装后检查两个展开标题的位置、间距及深浅色外观
2. 在不额外消耗真实额度的前提下核对已有耗尽账户；最后一个候选码仍应允许领取
3. 断网切换使用码开关：原状态保留，不应出现连续请求反转
4. 首次进入“我的”及断网重试个人信息
5. 更改 GitHub 日志文档后回到设置卡片，或手动检查更新；断网仍显示缓存
6. 从其他设备的最新账单回填上次设备消费；刚结束用水时旧账单不能覆盖新金额
7. 真实热水器开关、通知结束及小组件控制回归（本机没有执行这些操作）
