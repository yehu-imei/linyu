package com.hualala.linyu.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.max

/** 主题切换触发器：从按钮的根布局范围开始揭示。 */
data class ThemeRevealController(
    val toggle: (Rect) -> Unit = {},
    val pendingMode: ThemeMode? = null
)

val LocalThemeReveal = staticCompositionLocalOf { ThemeRevealController() }

/**
 * 顶层圆形揭示（Circular Reveal）主题切换容器。
 *
 * 关键设计：**内容只渲染一次**，主题通过 CompositionLocal 切换（重组，而非重建），
 * 因此切换时不会重跑 LaunchedEffect / 丢失状态，也就不会闪烁。
 * 过渡效果由一层"旧主题背景色"遮罩实现：遮罩填充圆形之外的区域，
 * 圆形半径由 0 扩散到全屏，视觉上新主题从点击点向外铺开。
 */
@Composable
fun CircularRevealThemeHost(
    themeModeState: MutableState<ThemeMode>,
    onThemeChanged: (ThemeMode) -> Unit,
    onThemePreview: ((ThemeMode) -> Unit)? = null,   // 在"内容切换点"回调，用于同步状态栏
    content: @Composable () -> Unit
) {
    val currentMode by themeModeState
    var isAnimating by remember { mutableStateOf(false) }
    // 与 isAnimating 分离：isAnimating 只表示"遮罩存在"，animationStarted 才表示"动画可以跑了"
    var animationStarted by remember { mutableStateOf(false) }
    var previewApplied by remember { mutableStateOf(false) }
    var revealBounds by remember { mutableStateOf(Rect.Zero) }
    var targetMode by remember { mutableStateOf(currentMode) }
    val animProgress = remember { Animatable(0f) }

    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { config.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }

    val controller = remember(currentMode, isAnimating, targetMode) {
        ThemeRevealController(
            toggle = { bounds ->
                if (!isAnimating) {
                    targetMode = if (currentMode == ThemeMode.LIGHT) ThemeMode.DARK else ThemeMode.LIGHT
                    revealBounds = bounds
                    // 同步重置，避免上一轮动画结束时的残留进度影响首帧显示
                    animationStarted = false
                    previewApplied = false
                    isAnimating = true
                }
            },
            pendingMode = targetMode.takeIf { isAnimating }
        )
    }

    // 阶段一：遮罩先进入 Composition，等待至少一帧绘制完成后，再启动动画
    LaunchedEffect(isAnimating) {
        if (isAnimating) {
            animProgress.snapTo(0f)
            withFrameNanos { }        // 让遮罩（alpha=0 初始态）先绘制一帧
            animationStarted = true
        }
    }

    // 阶段二：正式播放圆形揭示，动画结束后才提交主题状态
    LaunchedEffect(animationStarted) {
        if (animationStarted) {
            animProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 650,
                    easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
                )
            )
            themeModeState.value = targetMode
            onThemeChanged(targetMode)
            animationStarted = false
            isAnimating = false
        }
    }

    // 进度跨过 20%（即内容切换点）时同步状态栏，避免状态栏"慢半拍"。
    // 用 derivedStateOf 缓存，只有跨越阈值那一刻才触发重组，而不是每帧。
    val crossedThreshold by remember { derivedStateOf { animProgress.value >= 0.2f } }
    LaunchedEffect(animationStarted, crossedThreshold) {
        if (animationStarted && crossedThreshold && !previewApplied) {
            previewApplied = true
            onThemePreview?.invoke(targetMode)
        }
    }

    // 三种情况下都保持旧主题：
    // ① 非动画中；② 遮罩尚未就绪（消除首帧闪烁）；③ 遮罩渐显的前 20%（此时颜色突变会被遮罩挡住）
    // 同样用 derivedStateOf：进度从 0 走到 1 的过程中，这里只会重组 1 次（跨过 0.2 时）。
    val displayMode by remember {
        derivedStateOf {
            when {
                !isAnimating -> currentMode
                !animationStarted -> currentMode
                animProgress.value < 0.2f -> currentMode
                else -> targetMode
            }
        }
    }

    CompositionLocalProvider(
        LocalThemeMode provides themeModeState,
        LocalThemeReveal provides controller
    ) {
        Box(Modifier.fillMaxSize()) {
            LinYuThemeSpecific(displayMode) {
                Box(modifier = Modifier.fillMaxSize().background(LocalAppColors.current.Background)) {
                    content()
                }
            }

            // 过渡遮罩：旧主题背景色，填充圆形之外区域。
            // 前 20% 时间遮罩由透明渐显，避免点击瞬间"整屏突变"造成的闪烁；
            // 之后圆形半径从 0 扩散到全屏，新主题从点击点铺开。
            if (isAnimating) {
                val oldBg = if (targetMode == ThemeMode.LIGHT) DarkColors.Background else LightColors.Background
                val center = if (revealBounds == Rect.Zero) {
                    with(density) { Offset(screenWidthPx - 36.dp.toPx(), 48.dp.toPx()) }
                } else revealBounds.center
                // 动画全程露出按钮，点击后更新的图标不会被过渡遮罩盖住。
                val startRadius = (revealBounds.topLeft - revealBounds.center).getDistance()
                // ×1.15：多覆盖一点，避免圆角/边缘处残留旧主题的色块
                val maxRadius = max(
                    max(hypot(center.x, center.y), hypot(screenWidthPx - center.x, center.y)),
                    max(hypot(center.x, screenHeightPx - center.y), hypot(screenWidthPx - center.x, screenHeightPx - center.y))
                ) * 1.15f
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            // 在「绘制阶段」读取动画进度：动画每帧只触发重绘，不再触发重组
                            val progress = animProgress.value
                            val maskAlpha = (progress / 0.2f).coerceIn(0f, 1f)
                            val fraction = ((progress - 0.2f) / 0.8f).coerceIn(0f, 1f)
                            val radius = startRadius + fraction * (maxRadius - startRadius).coerceAtLeast(0f)
                            val path = Path().apply {
                                addOval(Rect(center = center, radius = radius))
                            }
                            clipPath(path, ClipOp.Difference) {
                                drawRect(oldBg, alpha = maskAlpha)
                            }
                        }
                )
            }
        }
    }
}
