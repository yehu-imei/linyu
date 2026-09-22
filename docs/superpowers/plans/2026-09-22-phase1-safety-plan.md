# LinYu 第一阶段安全修复实施计划

> **供智能体执行：** 必须使用 `superpowers:executing-plans`（当前会话执行）逐项完成本计划。所有任务使用复选框跟踪。

**目标：** 清理重复工作目录，并修复关阀失败误报、多设备监控缺失和退出登录破坏恢复状态三个严重问题。

**架构：** 以纯 Kotlin 的 `ShowerSafetyPolicy` 固化关阀、监控恢复和退出判定规则；Android Service 只负责执行策略和管理按设备划分的协程；持久化清理通过显式的“保留活跃恢复数据”参数区分主动退出和强制下线。

**技术栈：** Kotlin 2.0、Android SDK 36、协程、JUnit 4、Jetpack Compose、Android 前台服务。

**规格：** `docs/superpowers/specs/2026-09-22-phase1-safety-design.md`

## 全局约束

- 唯一源码仓库为 `C:\Users\22763\Documents\linyu-open-source`。
- 正常登录、扫描、开阀、账单和小组件视觉行为不得改变。
- `Failed` 和 `Unconfirmed` 都不得产生“使用结束”结果。
- 主动退出必须在存在活跃订单时被拒绝。
- 强制下线必须保留活跃订单、设备信息和计时数据。
- 所有生产行为修改必须先出现能够复现问题的失败测试。
- 不建立 Lint baseline，不隐藏现有错误。

---

### 任务 1：清理目录并统一本机配置

**文件：**
- 移动：`AdroidProjects/local.properties` → `linyu-open-source/local.properties`
- 移动：`AdroidProjects/hualala.jks` → `linyu-open-source/hualala.jks`
- 删除：`AdroidProjects` 下除 `.worktrees` 外的重复源码、缓存和发布暂存文件
- 删除：`linyu-open-source/.gradle`、`linyu-open-source/.kotlin`、`linyu-open-source/build`、`linyu-open-source/app/build`

**接口：** 无代码接口；输出是可独立构建的唯一仓库和被 Git 忽略的本机签名配置。

- [x] **步骤 1：验证源文件、目标目录和 Git 忽略规则**

运行只读检查，确认两个目标文件不存在、`*.jks` 和 `local.properties` 均被忽略，并解析 `KEYSTORE_FILE` 在迁移后指向仓库根目录的密钥。

- [x] **步骤 2：迁移签名配置与密钥**

使用显式绝对路径执行 `Move-Item`，不得覆盖已有目标。

- [x] **步骤 3：删除明确列出的重复文件与缓存**

仅删除规格中列出的绝对路径；保留 `.worktrees/phase1-safety`。这些内容不可从回收站恢复，但重复源码存在于 Git，构建缓存可以重新生成，历史发布说明存在于 GitHub Releases/CHANGELOG。

- [x] **步骤 4：验证旧目录和唯一仓库状态**

确认旧目录只剩 `.worktrees`，唯一仓库仍为干净 `master`，密钥和本机配置未被 Git 跟踪。

### 任务 2：删除缺失转换脚本的文档引用

**文件：**
- 修改：`design/device-icons/README.md`

**接口：** 文档仅保留 SVG 源图和生成物说明，不再声称仓库包含或可以执行 `svg2vector.py`。

- [x] **步骤 1：运行引用检查并确认当前失败**

运行：`rg -n "svg2vector|转换脚本|怎么重新转" design/device-icons/README.md`

预期：找到脚本文件、执行命令和脚本实现细节，证明文档与仓库不一致。

- [x] **步骤 2：删除脚本专属内容**

保留 SVG 来源、对应 Android drawable 和设计注意事项；删除文件表中的脚本、运行命令、硬编码路径警告和只能通过脚本理解的实现细节。

- [x] **步骤 3：重新运行引用检查**

预期：`rg` 无匹配；`git diff --check` 通过。

- [x] **步骤 4：提交文档修复**

提交信息：`docs: remove missing icon converter references`

### 任务 3：用纯 Kotlin 策略锁定安全行为

**文件：**
- 新建：`app/src/main/java/com/hualala/linyu/data/ShowerSafetyPolicy.kt`
- 新建：`app/src/test/java/com/hualala/linyu/data/ShowerSafetyPolicyTest.kt`
- 修改：`app/src/test/java/com/hualala/linyu/ExampleUnitTest.kt`（删除模板）

**接口：**
- 产出：`enum class CloseDisposition { COMPLETE, RESTORE_FAILED, RESTORE_UNCONFIRMED }`
- 产出：`fun closeDisposition(outcome: CloseOutcome): CloseDisposition`
- 产出：`fun monitorSerials(orders: List<ActiveOrder>): List<String>`
- 产出：`fun canLogoutVoluntarily(orders: List<ActiveOrder>): Boolean`

- [x] **步骤 1：编写关阀结果映射失败测试**

测试分别断言 `Closed` → `COMPLETE`、`Failed` → `RESTORE_FAILED`、`Unconfirmed` → `RESTORE_UNCONFIRMED`。生产策略尚不存在，因此测试编译失败。

- [x] **步骤 2：运行聚焦测试并确认红灯**

运行：`gradlew testDebugUnitTest --tests "*.ShowerSafetyPolicyTest"`

预期：因 `ShowerSafetyPolicy`/`CloseDisposition` 未定义而失败。

- [x] **步骤 3：实现最小关阀映射**

使用穷尽 `when` 映射三个密封类型，不提供默认分支。

- [x] **步骤 4：运行聚焦测试并确认绿灯**

预期：三个结果映射测试通过。

- [x] **步骤 5：添加多设备恢复与退出判定失败测试**

断言序列号去重且保持顺序、两个设备同时返回、空订单允许退出、任一活跃订单阻止退出。

- [x] **步骤 6：运行测试并确认新增断言失败**

预期：缺少 `monitorSerials` 和 `canLogoutVoluntarily` 导致编译失败。

- [x] **步骤 7：实现最小纯函数并删除模板测试**

`monitorSerials` 过滤空序列号并 `distinct()`；退出判定仅在列表为空时返回 true。

- [x] **步骤 8：运行全部单元测试**

运行：`gradlew testDebugUnitTest`

预期：全部通过且不再执行 `2 + 2 = 4` 模板测试。

- [x] **步骤 9：提交策略与测试**

提交信息：`test: define shower safety policies`

### 任务 4：修复后台关阀失败误报

**文件：**
- 修改：`app/src/main/java/com/hualala/linyu/service/ShowerWatchService.kt`
- 修改：`app/src/main/java/com/hualala/linyu/utils/Notifier.kt`
- 修改：`app/src/test/java/com/hualala/linyu/data/ShowerSafetyPolicyTest.kt`

**接口：** 消费任务 3 的 `closeDisposition`；新增按失败类型选择的用户提示，不改变 `CloseOutcome`。

- [x] **步骤 1：添加“非成功结果不得完成”失败测试**

以策略输出断言只有 `COMPLETE` 允许进入结算，两个恢复结果都要求保留重试入口。

- [x] **步骤 2：运行测试确认红灯**

预期：当前策略缺少 `mayAnnounceFinished` 行为，测试失败。

- [x] **步骤 3：为策略增加 `mayAnnounceFinished` 最小属性**

仅 `COMPLETE` 返回 true。

- [x] **步骤 4：统一 Service 的失败回滚分支**

`Failed` 和 `Unconfirmed` 都调用 `restoreActiveOrder`、清除 busy、刷新小组件、同步 App 内存并提前返回；仅 `COMPLETE` 进入结算和完成通知。

- [x] **步骤 5：分别使用失败与未确认文案**

`Failed` 显示服务端错误，`Unconfirmed` 显示网络不确定性；两者不得包含“已结束”。

- [x] **步骤 6：运行聚焦测试和全部单元测试**

预期：全部通过。

- [x] **步骤 7：提交关阀修复**

提交信息：`fix: retain active state when valve close fails`

### 任务 5：按设备管理监控服务

**文件：**
- 新建：`app/src/main/java/com/hualala/linyu/data/KeyedTaskRegistry.kt`
- 新建：`app/src/test/java/com/hualala/linyu/data/KeyedTaskRegistryTest.kt`
- 修改：`app/src/main/java/com/hualala/linyu/service/ShowerWatchService.kt`
- 修改：`app/src/main/java/com/hualala/linyu/utils/Notifier.kt`
- 修改：`app/src/test/java/com/hualala/linyu/data/ShowerSafetyPolicyTest.kt`

**接口：**
- 产出：`class KeyedTaskRegistry<T>`
- 产出：`fun getOrStart(key: String, starter: () -> T): T`
- 产出：`fun remove(key: String, expected: T): Boolean`
- 产出：`fun remove(key: String): T?`
- 产出：`fun isEmpty(): Boolean` 与 `fun size(): Int`
- 使用 `monitorSerials` 生成恢复集合；服务以 `KeyedTaskRegistry<Job>` 维护按 `snCode` 索引的任务；新增多设备汇总前台通知。

- [x] **步骤 1：增加服务重启规划测试**

断言同一 key 连续注册只调用一次 `starter`，两个不同 key 同时存在，删除其中一个不影响另一个，并且带 expected 的删除不能误删已经替换的任务。

- [x] **步骤 2：运行测试确认红灯**

预期：`KeyedTaskRegistry` 尚不存在，测试编译失败。

- [x] **步骤 3：实现按序列号启动和移除监控任务**

先实现通用且同步保护的 `KeyedTaskRegistry<T>` 使测试转绿，再用 `KeyedTaskRegistry<Job>` 替换单个 `watchJob`；同一设备幂等，不同设备并存；单个任务退出不停止其他设备。

- [x] **步骤 4：实现 START_STICKY 全量恢复**

Intent 有明确序列号时启动该设备；空 Intent 时读取全部活跃订单。无登录凭证时清理服务自身但不删除订单。

- [x] **步骤 5：修复主动结束的服务生命周期**

结束某台设备前取消它的监控；使用进行中结束计数；仅在任务映射和结束计数都为空时调用 `stopSelf()`。

- [x] **步骤 6：按设备读取名称和构建通知**

单设备通知带对应序列号的停止操作；多设备前台通知显示数量并只打开 App，不绑定含糊的停止操作。

- [x] **步骤 7：运行全部单元测试和 Debug 编译**

运行：`gradlew testDebugUnitTest assembleDebug`

预期：成功。

- [x] **步骤 8：提交多设备监控修复**

提交信息：`fix: monitor every active shower independently`

### 任务 6：区分主动退出与强制下线

**文件：**
- 修改：`app/src/main/java/com/hualala/linyu/utils/PrefsHelper.kt`
- 修改：`app/src/main/java/com/hualala/linyu/ui/MainViewModel.kt`
- 修改：`app/src/main/java/com/hualala/linyu/MainActivity.kt`
- 修改：`app/src/test/java/com/hualala/linyu/data/ShowerSafetyPolicyTest.kt`

**接口：**
- 修改：`PrefsHelper.clear(preserveActiveRecovery: Boolean = false)`
- 修改：`MainViewModel.logout(preserveActiveRecovery: Boolean = false)`
- 消费：`canLogoutVoluntarily(activeOrders)`

- [ ] **步骤 1：添加退出判定和保留键集合测试**

测试主动退出被活跃订单阻止；强制下线的清理策略保留 `activeOrders`、`lastDevice*`、`startedAt_*` 和 `autoDiscon_*`，但不保留认证字段。

- [ ] **步骤 2：运行测试确认红灯**

预期：当前没有保留恢复数据的清理策略，测试失败。

- [ ] **步骤 3：实现可测试的保留键判定**

增加纯函数 `shouldPreserveForRecovery(key)`，只允许规格列出的恢复键。

- [ ] **步骤 4：修改 PrefsHelper 清理逻辑**

主动退出维持完整清理；强制下线跳过恢复键，继续删除登录凭证、账户资料、账单缓存和账户消费缓存。

- [ ] **步骤 5：阻止活跃用水时主动退出**

退出弹窗检测持久化活跃订单；存在订单时只提供返回操作并提示先结束全部用水，不调用 logout/clear。

- [ ] **步骤 6：修复强制下线路径**

不再调用会清订单的 `stopShower(skipNetwork = true)`；改为保留恢复数据的 logout/clear，明确警告设备可能继续运行，并停止无法认证的前台服务。

- [ ] **步骤 7：运行单元测试和 Debug 编译**

运行：`gradlew testDebugUnitTest assembleDebug`

预期：成功。

- [ ] **步骤 8：提交退出修复**

提交信息：`fix: preserve active recovery state on forced logout`

### 任务 7：完整验证与收尾

**文件：**
- 修改：`app/src/main/java/com/hualala/linyu/utils/Notifier.kt`（在实际发送通知前显式检查 Android 13+ 通知权限）
- 不创建：`lint-baseline.xml`

**接口：** 无新接口；验证本阶段全部交付结果。

- [ ] **步骤 1：运行格式与差异检查**

运行：`git diff --check`，确认无空白错误和意外生成文件。

- [ ] **步骤 2：运行全部单元测试和 Debug 构建**

运行：`gradlew testDebugUnitTest assembleDebug`

预期：成功。

- [ ] **步骤 3：运行 Release 构建**

将被忽略的本机签名文件临时提供给 worktree，运行 `gradlew assembleRelease -x lintVitalRelease`，预期签名 Release 构建成功；验证后从 worktree 删除本机私密副本。

- [ ] **步骤 4：运行 Lint 并记录实际结果**

运行：`gradlew lintDebug`。修复由本阶段修改引入或位于本阶段修改行的错误；其余历史问题记录到最终报告，不建立基线。

- [ ] **步骤 5：检查 Git 与旧工作目录**

确认分支只有计划内提交；旧目录除活动 worktree 外没有重复源码、构建缓存或敏感发布资料；唯一仓库中的 `local.properties` 和 `hualala.jks` 为 ignored。

- [ ] **步骤 6：提交必要的验证修正**

若通知权限检查产生独立修正，提交信息：`fix: check notification permission before posting`。

- [ ] **步骤 7：准备集成汇报**

列出提交、测试证据、仍存在的 Lint 历史问题、需要实机验证的多设备和厂商 ROM 风险。
