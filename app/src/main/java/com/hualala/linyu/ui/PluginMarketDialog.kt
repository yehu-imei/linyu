package com.hualala.linyu.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import com.hualala.linyu.R
import com.hualala.linyu.data.PluginListing
import com.hualala.linyu.data.PluginManifest
import com.hualala.linyu.data.PluginRepository
import com.hualala.linyu.ui.theme.AppColors
import com.hualala.linyu.utils.BackgroundManager
import com.hualala.linyu.utils.UserFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val PluginCardShape = RoundedCornerShape(22.dp)
private val PluginListPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

@Composable
internal fun PluginManagementScreen(
    installed: List<PluginManifest>,
    onMove: (String, Int) -> Unit,
    onInstall: (PluginManifest) -> Boolean,
    onUninstall: (String) -> Boolean,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentInstalled by rememberUpdatedState(installed)
    var showingMarket by rememberSaveable { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf(emptyList<PluginListing>()) }
    var loadingCatalog by remember { mutableStateOf(false) }
    var catalogFailed by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<PluginManifest?>(null) }
    var pendingDelete by remember { mutableStateOf<List<String>>(emptyList()) }
    var selection by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val selecting = selection != null
    val selected = installed.filter { it.id in selection.orEmpty() }
    val busy = installing || loadingCatalog
    var githubLogins by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    val authoredPlugins = (installed + catalog.map { it.manifest })
        .filter { it.authorGithubId != null }
        .distinctBy { it.authorGithubId }
    LaunchedEffect(authoredPlugins) {
        if (authoredPlugins.isEmpty()) return@LaunchedEffect
        try {
            githubLogins = githubLogins + PluginRepository.fetchGithubLogins(authoredPlugins)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Plugin descriptions can still use their bundled attribution when GitHub is unavailable.
        }
    }

    fun checkCatalog(reportUpdates: Boolean) {
        if (loadingCatalog) return
        loadingCatalog = true
        catalogFailed = false
        scope.launch {
            try {
                val entries = PluginRepository.fetchCatalog()
                catalog = entries
                if (reportUpdates) {
                    val count = currentInstalled.count { updateFor(it, entries) != null }
                    onMessage(when {
                        currentInstalled.isEmpty() -> "暂无已安装的插件"
                        count == 0 -> "插件均为最新版本"
                        else -> "有 $count 个插件可更新"
                    })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                catalog = emptyList()
                catalogFailed = true
                if (reportUpdates) onMessage("检查更新失败，请重试")
            } finally {
                loadingCatalog = false
            }
        }
    }

    fun install(success: String, failure: String, load: suspend () -> PluginManifest) {
        if (installing) return
        installing = true
        scope.launch {
            try {
                if (onInstall(load())) {
                    onMessage(success)
                } else {
                    onMessage("安装失败，请检查插件版本")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                onMessage(UserFeedback.message(e.message, failure))
            } catch (_: Exception) {
                onMessage(failure)
            } finally {
                installing = false
            }
        }
    }

    fun updateSelected() {
        if (busy || selected.isEmpty()) return
        val targets = selected.toList()
        installing = true
        scope.launch {
            try {
                try {
                    catalog = PluginRepository.fetchCatalog()
                    catalogFailed = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    catalog = emptyList()
                    catalogFailed = true
                    throw e
                }
                val updates = targets.mapNotNull { updateFor(it, catalog) }
                if (updates.isEmpty()) {
                    onMessage("选中的插件均为最新版本")
                    return@launch
                }
                val failed = updates.filterNot { entry ->
                    try {
                        onInstall(PluginRepository.download(entry))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                }
                val updatedCount = updates.size - failed.size
                if (updatedCount > 0 && selection != null) {
                    selection = if (failed.isEmpty()) null
                        else ArrayList(failed.map { it.manifest.id })
                }
                onMessage(when {
                    updatedCount == 0 -> "更新失败，请重试"
                    failed.isNotEmpty() -> "已更新 $updatedCount 个，${failed.size} 个失败"
                    else -> "已更新 $updatedCount 个插件"
                })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onMessage("检查更新失败，请重试")
            } finally {
                installing = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) install("安装成功", "无法安装此文件，请选择兼容的插件包") {
            PluginRepository.importFile(context, uri)
        }
    }

    LaunchedEffect(showingMarket) {
        if (showingMarket) checkCatalog(reportUpdates = false)
    }

    fun back() {
        when {
            pendingDelete.isNotEmpty() -> pendingDelete = emptyList()
            detail != null -> detail = null
            selecting -> selection = null
            showingMarket -> showingMarket = false
            else -> onDismiss()
        }
    }

    BackHandler(onBack = ::back)

    Box(Modifier.fillMaxSize().background(AppColors.Background)) {
        AppBackgroundLayer(BackgroundManager.SCOPE_HOME)
        val modalOpen = detail != null || pendingDelete.isNotEmpty()
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .then(if (modalOpen) Modifier.clearAndSetSemantics {} else Modifier)
        ) {
            // 标题栏：固定在顶部（不随列表滚动）
            Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
                val title = when {
                    showingMarket -> "插件仓库"
                    selecting -> "已选 ${selected.size} 项"
                    else -> "插件管理"
                }
                SettingsPageHeader(
                    title,
                    ::back,
                    backLabel = if (selecting) "退出多选" else "返回",
                    backIcon = if (selecting) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack
                ) {
                    if (showingMarket) {
                        IconButton(onClick = { checkCatalog(reportUpdates = false) }, enabled = !busy) {
                            Icon(Icons.Default.Refresh, "刷新插件仓库", tint = AppColors.TextPrimary)
                        }
                    } else if (selecting) {
                        IconButton(onClick = ::updateSelected, enabled = selected.isNotEmpty() && !busy) {
                            Icon(painterResource(R.drawable.ic_plugin_update), "更新选中的插件")
                        }
                        IconButton(
                            onClick = { pendingDelete = selected.map { it.id } },
                            enabled = selected.isNotEmpty() && !busy
                        ) {
                            Icon(Icons.Default.Delete, "删除选中的插件")
                        }
                    } else {
                        IconButton(
                            onClick = { checkCatalog(reportUpdates = true) },
                            enabled = !busy
                        ) {
                            Icon(painterResource(R.drawable.ic_plugin_update), "检查插件更新")
                        }
                        Box {
                            IconButton(onClick = { addMenuOpen = true }, enabled = !installing) {
                                Icon(Icons.Default.Add, "添加插件", tint = AppColors.TextPrimary)
                            }
                            DropdownMenu(
                                expanded = addMenuOpen,
                                onDismissRequest = { addMenuOpen = false },
                                shape = RoundedCornerShape(16.dp),
                                containerColor = AppColors.SolidSurface
                            ) {
                                PluginAddMenuItem("浏览插件仓库", ImageVector.vectorResource(R.drawable.ic_plugin_github)) {
                                    addMenuOpen = false
                                    showingMarket = true
                                }
                                PluginAddMenuItem("选择文件安装", ImageVector.vectorResource(R.drawable.ic_plugin_file_add)) {
                                    addMenuOpen = false
                                    picker.launch(arrayOf("*/*"))
                                }
                            }
                        }
                    }
                }
            }
            if (installing || loadingCatalog) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = AppColors.Accent)
            }

            if (showingMarket) {
                val entries = catalog
                when {
                    entries.isEmpty() && catalogFailed -> PluginMessage("无法读取插件目录") {
                        TextButton(onClick = { checkCatalog(reportUpdates = false) }) { Text("重试") }
                    }
                    entries.isEmpty() && loadingCatalog -> Unit
                    entries.isEmpty() -> PluginMessage("暂无可用插件")
                    else -> LazyColumn(
                        contentPadding = PluginListPadding,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(entries, key = { it.manifest.id }) { entry ->
                            val plugin = entry.manifest
                            val current = installed.firstOrNull { it.id == plugin.id }
                            val canUpdate = current != null && PluginRepository.isUpdate(current, plugin)
                            MarketPluginCard(
                                plugin = plugin,
                                author = plugin.displayAuthor(githubLogins),
                                installed = current != null,
                                canUpdate = canUpdate,
                                installing = busy,
                                onDetails = { detail = plugin },
                                onDownload = {
                                    install(if (canUpdate) "更新成功" else "安装成功", "下载失败，请重试") {
                                        PluginRepository.download(entry)
                                    }
                                }
                            )
                        }
                    }
                }
            } else if (installed.isEmpty()) {
                PluginMessage("暂无已安装的插件")
            } else {
                InstalledPluginList(
                    installed = installed,
                    catalog = catalog,
                    busy = busy,
                    selection = selection,
                    onMove = onMove,
                    onDetails = { detail = it },
                    onSelect = { selection = arrayListOf(it) },
                    onToggleSelected = { id ->
                        val current = selection.orEmpty()
                        selection = ArrayList(if (id in current) current - id else current + id)
                    },
                    onUpdate = { listing ->
                        install("更新成功", "更新失败，请重试") {
                            PluginRepository.download(listing)
                        }
                    },
                    onDelete = { pendingDelete = listOf(it.id) }
                )
            }
        }
        detail?.let { plugin ->
            PluginOverlay(pane = "插件详情", onDismiss = { detail = null }) {
                Text(plugin.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Text("作者 ${plugin.displayAuthor(githubLogins)} · v${plugin.version}",
                    fontSize = 13.sp, color = AppColors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (plugin.permissions.isNotEmpty()) {
                    // v2：把插件声明访问的数据/动作直接摆给用户看（不是装饰，是知情权）
                    Text(
                        "权限：" + plugin.permissions.joinToString("、") {
                            PluginRepository.permissionLabel(it)
                        },
                        fontSize = 13.sp, color = AppColors.TextSecondary
                    )
                }
                val matchingListing = catalog.firstOrNull { it.manifest.id == plugin.id }
                Text(plugin.details ?: matchingListing?.manifest?.details ?: plugin.description,
                    fontSize = 15.sp, color = AppColors.TextPrimary)
            }
        }
        if (pendingDelete.isNotEmpty()) {
            val deleteTargets = pendingDelete
            ScreenCenteredAlertDialog(
                onDismissRequest = { pendingDelete = emptyList() },
                title = { Text("删除插件") },
                text = { Text(if (deleteTargets.size == 1) "确定删除这个插件？"
                    else "确定删除选中的 ${deleteTargets.size} 个插件？") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDelete = emptyList()
                        val failed = deleteTargets.filterNot(onUninstall)
                        val deletedCount = deleteTargets.size - failed.size
                        if (selection != null) {
                            selection = if (failed.isEmpty()) null else ArrayList(failed)
                        }
                        onMessage(when {
                            deletedCount == 0 -> "删除失败，请重试"
                            failed.isNotEmpty() -> "已删除 $deletedCount 个，${failed.size} 个失败"
                            deleteTargets.size == 1 -> "已删除"
                            else -> "已删除 $deletedCount 个插件"
                        })
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { pendingDelete = emptyList() }) { Text("取消") } }
            )
        }
    }
}

private fun updateFor(plugin: PluginManifest, catalog: List<PluginListing>): PluginListing? =
    catalog.find { PluginRepository.isUpdate(plugin, it.manifest) }

private fun PluginManifest.displayAuthor(githubLogins: Map<Long, String>): String =
    authorGithubId?.let(githubLogins::get)?.let { "@$it" } ?: author

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstalledPluginList(
    installed: List<PluginManifest>,
    catalog: List<PluginListing>,
    busy: Boolean,
    selection: List<String>?,
    onMove: (String, Int) -> Unit,
    onDetails: (PluginManifest) -> Unit,
    onSelect: (String) -> Unit,
    onToggleSelected: (String) -> Unit,
    onUpdate: (PluginListing) -> Unit,
    onDelete: (PluginManifest) -> Unit
) {
    val list = rememberLazyListState()
    val move by rememberUpdatedState(onMove)
    var previewOrder by remember { mutableStateOf<List<String>?>(null) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragBy by remember { mutableFloatStateOf(0f) }
    var dragStartY by remember { mutableFloatStateOf(0f) }
    val canReorder = selection == null && installed.size > 1 && !busy

    fun resetDrag() {
        draggedId = null
        dragBy = 0f
        previewOrder = null
    }

    val displayed = previewOrder?.let { restorePluginOrder(installed, it) } ?: installed
    LazyColumn(
        state = list,
        contentPadding = PluginListPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = draggedId == null
    ) {
        itemsIndexed(displayed, key = { _, plugin -> plugin.id }) { index, plugin ->
            val update = updateFor(plugin, catalog)
            val active = draggedId == plugin.id
            val dragGesture = if (canReorder) Modifier.pointerInput(plugin.id, installed) {
                detectDragGestures(
                    onDragStart = {
                        val initial = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == plugin.id }
                            ?: return@detectDragGestures
                        previewOrder = installed.map { it.id }
                        draggedId = plugin.id
                        dragBy = 0f
                        dragStartY = initial.offset.toFloat()
                    },
                    onDragEnd = {
                        val targetIndex = previewOrder?.indexOf(plugin.id) ?: -1
                        resetDrag()
                        if (targetIndex >= 0) move(plugin.id, targetIndex)
                    },
                    onDragCancel = ::resetDrag
                ) { change, delta ->
                    change.consume()
                    if (draggedId != plugin.id) return@detectDragGestures
                    dragBy += delta.y
                    val order = previewOrder ?: return@detectDragGestures
                    val visible = list.layoutInfo.visibleItemsInfo
                    val origin = visible.firstOrNull { it.key == plugin.id } ?: return@detectDragGestures
                    // Slot offsets still describe the previous order until the lazy list is remeasured.
                    if (origin.index != order.indexOf(plugin.id)) return@detectDragGestures
                    val center = dragStartY + dragBy + origin.size / 2f
                    val above = visible.firstOrNull {
                        it.index < origin.index && center < it.offset + it.size / 2f
                    }
                    val below = visible.lastOrNull {
                        it.index > origin.index && center > it.offset + it.size / 2f
                    }
                    val target = above ?: below
                    if (target != null) previewOrder = movePlugin(order, plugin.id, target.index)
                }
            } else Modifier
            val reorderAccessibility = if (canReorder) Modifier.semantics {
                contentDescription = "拖动排序"
                customActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction("上移") { move(plugin.id, index - 1); true })
                    if (index < installed.lastIndex) add(CustomAccessibilityAction("下移") { move(plugin.id, index + 1); true })
                }
            } else Modifier

            val selected = plugin.id in selection.orEmpty()
            val placement = if (active || selection != null) Modifier else {
                Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
            }
            val surfaceColor = when {
                selected -> AppColors.Accent.copy(alpha = 0.12f).compositeOver(AppColors.SolidSurface)
                selection != null -> AppColors.SolidSurface
                else -> AppColors.Card
            }
            val interaction = if (selection != null) {
                Modifier.toggleable(selected, enabled = !busy, role = Role.Checkbox) {
                    onToggleSelected(plugin.id)
                }
            } else {
                Modifier.combinedClickable(
                    role = Role.Button,
                    enabled = !busy,
                    onLongClickLabel = "选择插件",
                    onLongClick = { onSelect(plugin.id) },
                    onClick = { onDetails(plugin) }
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .then(placement)
                    .zIndex(if (active) 1f else 0f)
                    .graphicsLayer {
                        val slot = if (active) list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == plugin.id } else null
                        translationY = if (slot != null) dragStartY + dragBy - slot.offset else 0f
                    }
                    .clip(PluginCardShape)
                    .then(interaction),
                shape = PluginCardShape,
                color = surfaceColor,
                border = BorderStroke(0.8.dp, if (selected) AppColors.Accent else AppColors.Border)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(plugin.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = AppColors.TextPrimary)
                        val updateColor = AppColors.Danger
                        Text(
                            buildAnnotatedString {
                                append("v${plugin.version}")
                                if (update != null) withStyle(SpanStyle(color = updateColor)) { append("（可更新）") }
                            },
                            fontSize = 13.sp,
                            color = AppColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (selection != null) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = null,
                                enabled = !busy,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = AppColors.Accent,
                                    uncheckedColor = AppColors.TextSecondary
                                )
                            )
                        }
                    } else {
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuOpen = true }, enabled = !busy) {
                                Icon(Icons.Default.MoreVert, "${plugin.name}操作", tint = AppColors.TextSecondary)
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                                shape = RoundedCornerShape(14.dp),
                                containerColor = AppColors.SolidSurface
                            ) {
                                DropdownMenuItem(
                                    text = { Text("更新") },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_plugin_update), null) },
                                    enabled = update != null && !busy,
                                    onClick = {
                                        menuOpen = false
                                        update?.let(onUpdate)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除", color = AppColors.Danger) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = AppColors.Danger) },
                                    enabled = !busy,
                                    onClick = {
                                        menuOpen = false
                                        onDelete(plugin)
                                    }
                                )
                            }
                        }
                        Box(
                            Modifier.size(48.dp).then(dragGesture).then(reorderAccessibility),
                            contentAlignment = Alignment.Center
                        ) {
                            val color = AppColors.TextSecondary.copy(alpha = if (canReorder) 1f else .35f)
                            Canvas(Modifier.size(20.dp)) {
                                for (ratio in listOf(.37f, .63f)) {
                                    drawLine(color, Offset(size.width * .12f, size.height * ratio),
                                        Offset(size.width * .88f, size.height * ratio), 2.dp.toPx(), StrokeCap.Round)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketPluginCard(
    plugin: PluginManifest,
    author: String,
    installed: Boolean,
    canUpdate: Boolean,
    installing: Boolean,
    onDetails: () -> Unit,
    onDownload: () -> Unit
) {
    BaseCard(Modifier.clip(PluginCardShape).clickable(role = Role.Button, onClick = onDetails)) {
        Row(
            Modifier.fillMaxWidth().padding(15.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(plugin.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                    color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("作者 $author · v${plugin.version}", fontSize = 12.sp, color = AppColors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(plugin.description, fontSize = 13.sp, color = AppColors.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!installed || canUpdate) {
                Button(
                    onClick = onDownload,
                    enabled = !installing,
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Accent,
                        contentColor = Color.White,
                        disabledContainerColor = AppColors.Accent.copy(alpha = 0.55f),
                        disabledContentColor = Color.White.copy(alpha = 0.55f)
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp)
                ) {
                    Text(if (canUpdate) "更新" else "安装", fontSize = 14.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1)
                }
            } else {
                Text("已安装", fontSize = 12.sp, color = AppColors.TextSecondary,
                    modifier = Modifier.widthIn(min = 55.dp), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun PluginAddMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontWeight = FontWeight.Medium, color = AppColors.TextPrimary) },
        onClick = onClick,
        leadingIcon = {
            Box(
                Modifier.size(34.dp).background(AppColors.Accent.copy(alpha = .12f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, modifier = Modifier.size(19.dp), tint = AppColors.Accent)
            }
        },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp)
    )
}

@Composable
private fun PluginMessage(text: String, action: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(top = 56.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text, color = AppColors.TextSecondary, fontSize = 14.sp)
        action()
    }
}

@Composable
private fun PluginOverlay(pane: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = ScreenCenteredDialogProperties
    ) {
        CenterDialogWindowOnScreen()
        Surface(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().semantics { paneTitle = pane },
            shape = PluginCardShape,
            color = AppColors.SolidSurface
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}
