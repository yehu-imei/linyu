# LinYu 消费刷新与趋势实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复桌面小组件“上次消费”延迟更新，增加账单消费趋势，并完成四项已批准的工程优化。

**Architecture:** 将服务端日期解析、账单聚合和待结算核对拆成纯 Kotlin 模块，通过单元测试固定行为。`MainViewModel` 保留最近 20 条列表并新增完整账单历史；结算入口只负责写入/保存待核对状态，Service 和 ViewModel 在状态变化后统一刷新小组件。趋势 UI 使用 Compose Canvas，不引入图表依赖。

**Tech Stack:** Kotlin 2.0、Android 26+、Jetpack Compose、Material 3、JUnit 4、Gson、java.time。

**Spec:** `docs/superpowers/specs/2026-09-22-consumption-insights-design.md`

## Global Constraints

- 不引入第三方图表库。
- `billList` 继续只保存最近 20 条，现有列表、余额估算和小组件账单缓存行为不变。
- 零消费不覆盖历史“上次消费”；未知金额不能显示成零。
- APK 仅在本地构建，放入下载目录并使用大写 `.APK` 后缀，不推送 GitHub。
- 不修改开关阀成功、失败、未知三态语义。

---

### Task 1: 线程安全账单日期解析

**Files:**
- Create: `app/src/main/java/com/hualala/linyu/data/BillDateParser.kt`
- Modify: `app/src/main/java/com/hualala/linyu/data/ShowerController.kt`
- Test: `app/src/test/java/com/hualala/linyu/data/BillDateParserTest.kt`

**Interfaces:**
- Produces: `BillDateParser.parseLocalDateTime(String): LocalDateTime?`
- Produces: `BillDateParser.toEpochMillis(String, ZoneId = ZoneId.systemDefault()): Long`

- [ ] **Step 1: Write failing parameterized-style tests**

覆盖 `yyyy-MM-dd HH:mm:ss`、ISO `T`、毫秒格式、非法日期，并用固定 `ZoneId` 验证 epoch。

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew testDebugUnitTest --tests com.hualala.linyu.data.BillDateParserTest`
Expected: FAIL because `BillDateParser` does not exist.

- [ ] **Step 3: Implement the immutable formatter utility and replace shared parsers**

使用三个 `DateTimeFormatter`，逐一 `runCatching` 解析；`ShowerController.billTimeMs` 委托该工具，月份格式使用 `YearMonth.now().toString()`。

- [ ] **Step 4: Run focused and full unit tests**

Run: `./gradlew testDebugUnitTest --tests com.hualala.linyu.data.BillDateParserTest`
Expected: PASS.

- [ ] **Step 5: Commit**

Commit message: `refactor: make bill date parsing thread safe`

### Task 2: 消费统计纯逻辑

**Files:**
- Create: `app/src/main/java/com/hualala/linyu/data/SpendingAnalytics.kt`
- Test: `app/src/test/java/com/hualala/linyu/data/SpendingAnalyticsTest.kt`

**Interfaces:**
- Produces: `enum class TrendRange { LAST_7_DAYS, THIS_MONTH }`
- Produces: `data class DailySpend(val date: LocalDate, val amount: Double, val count: Int)`
- Produces: `data class SpendingSummary(val points: List<DailySpend>, val total: Double, val count: Int, val average: Double)`
- Produces: `SpendingAnalytics.summarize(List<BillItem>, TrendRange, LocalDate): SpendingSummary`

- [ ] **Step 1: Write failing aggregation tests**

覆盖近 7 天补零、同日多笔、本月从 1 日开始、跨月排除、非法日期和金额忽略、空列表全零。

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew testDebugUnitTest --tests com.hualala.linyu.data.SpendingAnalyticsTest`
Expected: FAIL because analytics types do not exist.

- [ ] **Step 3: Implement minimal aggregation**

通过 `BillDateParser` 解析日期，先过滤范围，再按 `LocalDate` 聚合；生成完整日期序列并计算 total/count/average。

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `./gradlew testDebugUnitTest --tests com.hualala.linyu.data.SpendingAnalyticsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

Commit message: `feat: add spending trend aggregation`

### Task 3: 延迟结算核对与小组件刷新

**Files:**
- Create: `app/src/main/java/com/hualala/linyu/data/PendingSettlement.kt`
- Modify: `app/src/main/java/com/hualala/linyu/utils/PrefsHelper.kt`
- Modify: `app/src/main/java/com/hualala/linyu/data/ShowerController.kt`
- Modify: `app/src/main/java/com/hualala/linyu/service/ShowerWatchService.kt`
- Modify: `app/src/main/java/com/hualala/linyu/ui/MainViewModel.kt`
- Test: `app/src/test/java/com/hualala/linyu/data/PendingSettlementTest.kt`

**Interfaces:**
- Produces: `data class PendingSettlement(snCode, orderNo, startedAt, deviceName, createdAt)`
- Produces: `SettlementReconciler.reconcile(pending, bills, nowMs): SettlementReconciliation`
- Produces: `PrefsHelper.getPendingSettlements()` and `savePendingSettlements(List<PendingSettlement>)`
- Consumes: `BillDateParser.toEpochMillis` for legacy time-window matching.

- [ ] **Step 1: Write failing reconciliation tests**

覆盖订单号精确匹配、多设备隔离、正金额写入决策、零金额完成但不覆盖、未匹配保留、超过 7 天过期、旧记录设备名与时间窗兜底。

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./gradlew testDebugUnitTest --tests com.hualala.linyu.data.PendingSettlementTest`
Expected: FAIL because pending settlement types do not exist.

- [ ] **Step 3: Implement reconciliation and persistence**

待核对记录以 Gson JSON 存入现有偏好；同一 `snCode` 新记录替换旧记录。`settleAmount` 得到正金额或明确零时删除相应记录，返回 `null` 时保存记录。

- [ ] **Step 4: Connect refresh nodes**

`ShowerWatchService.finish` 和 `doFinish` 在结算结束后调用 `LinYuWidget.refreshAll`。`MainViewModel.loadBills` 对完整账单运行 reconciliation，按结果调用 `PrefsHelper.recordConsume`、保存剩余记录并刷新小组件。

- [ ] **Step 5: Run focused and full tests**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

Commit message: `fix: reconcile delayed consumption updates`

### Task 4: 账单趋势界面

**Files:**
- Create: `app/src/main/java/com/hualala/linyu/ui/SpendingTrendCard.kt`
- Modify: `app/src/main/java/com/hualala/linyu/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/hualala/linyu/ui/WalletScreen.kt`

**Interfaces:**
- `MainViewModel.billHistory: List<BillItem>` exposes the complete three-month response.
- `SpendingTrendCard(bills: List<BillItem>, modifier: Modifier = Modifier)` owns range selection and selected point.
- Consumes: `SpendingAnalytics.summarize`.

- [ ] **Step 1: Add complete history state without changing the 20-row list**

Set `billHistory = all.toList()` after the three monthly requests; clear it on logout. Preserve `billList = all.take(20)` and widget cache size.

- [ ] **Step 2: Implement the compact statistics header**

Use a two-option segmented control and three evenly sized metrics for total, count and average.

- [ ] **Step 3: Implement Canvas trend interaction**

Draw grid baseline, filled line path and stable points. Map tap x-coordinate to nearest point and show its date/amount/count. Limit x-axis labels to first/middle/last for 7 days and evenly spaced labels for a month.

- [ ] **Step 4: Insert the section between balance and bill list**

Show the trend once bill loading has completed; while loading, keep dimensions stable with a progress placeholder.

- [ ] **Step 5: Compile and inspect layout warnings**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL with no new compiler errors.

- [ ] **Step 6: Commit**

Commit message: `feat: add consumption trend to wallet`

### Task 5: Approved security and compatibility optimizations

**Files:**
- Modify: `app/src/main/java/com/hualala/linyu/ui/LoginViewModel.kt`
- Modify: `app/src/main/java/com/hualala/linyu/QrScanActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:** No new public interfaces.

- [ ] **Step 1: Clear one-time secrets after requests**

Wrap password and SMS login bodies in `try/finally`; clear only the credential used by that request in `finally`, while retaining phone and error state.

- [ ] **Step 2: Declare optional camera hardware**

Add `<uses-feature android:name="android.hardware.camera" android:required="false" />` next to camera permission.

- [ ] **Step 3: Opt in to CameraX image access**

Import `androidx.camera.core.ExperimentalGetImage` and annotate the narrow `startCamera` function with `@OptIn(ExperimentalGetImage::class)`.

- [ ] **Step 4: Run compile and Lint**

Run: `./gradlew assembleDebug lintDebug`
Expected: Debug build succeeds; the two targeted CameraX/Manifest errors are absent. Any remaining historical RemoteViews tint errors are reported.

- [ ] **Step 5: Commit**

Commit message: `fix: tighten login and camera compatibility`

### Task 6: Documentation and final verification

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `PROJECT.md`
- Modify: `README.md` only if the user-visible feature list needs the trend entry.

**Interfaces:** No code interfaces.

- [ ] **Step 1: Document behavior and completed audit items**

Record the delayed-settlement reconciliation, trend ranges/statistics, no chart dependency, thread-safe dates, secret clearing and camera compatibility. Clearly distinguish completed work from remaining review recommendations.

- [ ] **Step 2: Run full verification**

Run: `./gradlew testDebugUnitTest assembleDebug lintDebug`
Expected: tests and Debug build pass; Lint result is captured exactly.

- [ ] **Step 3: Build and verify Release APK**

Run: `./gradlew assembleRelease -x lintVitalRelease`, then `apksigner verify --verbose --print-certs`.
Expected: signed APK verifies with the existing Hualala certificate.

- [ ] **Step 4: Copy APK to Downloads**

Copy to `C:\Users\22763\Downloads\LinYu-3.0.4-consumption-insights.APK`; verify SHA-256 equals the build output.

- [ ] **Step 5: Review diff and commit docs**

Commit message: `docs: document consumption insights update`

- [ ] **Step 6: Merge locally only**

Fast-forward the verified branch into local `master`, rerun unit tests on the merged tree, remove the temporary worktree/branch, and confirm `origin/master` remains untouched.
