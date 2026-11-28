package com.hualala.linyu.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.material.ripple
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.hualala.linyu.data.InstalledPlugins
import com.hualala.linyu.ui.installedPluginIds
import com.hualala.linyu.utils.BackgroundManager
import com.hualala.linyu.utils.BackgroundState

enum class ThemeMode { LIGHT, DARK }

// ── Light palette（液态玻璃：半透明卡片 + 微光描边） ──
val LightColors = AppColorSet(
    Background = Color(0xFFF1F5F9),
    Card = Color(0xB8FFFFFF),          // 72% 透白玻璃
    SolidSurface = Color(0xFFFFFFFF),  // 弹窗等需完全遮挡的场合用纯色
    Primary = Color(0xFF0D1117),
    Accent = Color(0xFF2563EB),
    TextPrimary = Color(0xFF111827),
    TextSecondary = Color(0xFF6B7280),
    Success = Color(0xFF22C55E), Warning = Color(0xFFF59E0B),
    Danger = Color(0xFFEF4444), ActiveBg = Color(0xFFFFF7ED),
    SurfaceVariant = Color(0xFFF0F0F0),
    Border = Color(0x14000000),        // 玻璃微光描边（极淡，仅提示圆角轮廓）
    isDark = false
)

// ── Dark palette（液态玻璃：半透明磨砂 + 微光描边） ──
val DarkColors = AppColorSet(
    Background = Color(0xFF0E131D),
    Card = Color(0xBF1D232E),          // 75% 磨砂玻璃
    SolidSurface = Color(0xFF161B22),
    Primary = Color(0xFFE6EDF3),
    Accent = Color(0xFF58A6FF),
    TextPrimary = Color(0xFFE6EDF3),
    TextSecondary = Color(0xFF8B949E),
    Success = Color(0xFF3FB950), Warning = Color(0xFFD29922),
    Danger = Color(0xFFF85149), ActiveBg = Color(0xFF1B1F23),
    SurfaceVariant = Color(0xFF21262D),
    Border = Color(0x14FFFFFF),
    isDark = true
)

data class AppColorSet(
    val Background: Color, val Card: Color,
    val SolidSurface: Color = Color.White,
    val Primary: Color, val Accent: Color,
    val TextPrimary: Color, val TextSecondary: Color,
    val Success: Color, val Warning: Color,
    val Danger: Color, val ActiveBg: Color,
    val SurfaceVariant: Color,
    val Border: Color = Color(0x18FFFFFF),
    val isDark: Boolean = false
)

val LocalAppColors = staticCompositionLocalOf { LightColors }
val LocalThemeMode = compositionLocalOf { mutableStateOf(ThemeMode.LIGHT) }

/** Convenience: use AppColors.xxx in composable functions */
object AppColors {
    val Background: Color @Composable get() = LocalAppColors.current.Background
    val Card: Color @Composable get() = LocalAppColors.current.Card
    val SolidSurface: Color @Composable get() = LocalAppColors.current.SolidSurface
    val Primary: Color @Composable get() = LocalAppColors.current.Primary
    val Accent: Color @Composable get() = LocalAppColors.current.Accent
    val TextPrimary: Color @Composable get() = LocalAppColors.current.TextPrimary
    val TextSecondary: Color @Composable get() = LocalAppColors.current.TextSecondary
    val Success: Color @Composable get() = LocalAppColors.current.Success
    val Warning: Color @Composable get() = LocalAppColors.current.Warning
    val Danger: Color @Composable get() = LocalAppColors.current.Danger
    val ActiveBg: Color @Composable get() = LocalAppColors.current.ActiveBg
    val Border: Color @Composable get() = LocalAppColors.current.Border
    val isDark: Boolean @Composable get() = LocalAppColors.current.isDark
}

/**
 * 按「指定模式」渲染主题。
 * 与 [LinYuTheme] 的区别：它不读取全局状态，而是渲染传入的 mode，
 * 供主题切换动画同时叠加两层（旧主题 + 新主题）使用。
 */
@Composable
fun LinYuThemeSpecific(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = (mode == ThemeMode.DARK)
    val base = if (dark) DarkColors else LightColors
    // 自定义背景提取的主题色：只覆盖「强调色」，不参与 Light / Dark 的判定与切换
    val bgTheme = if (InstalledPlugins.BACKGROUND in installedPluginIds()) {
        BackgroundState.config(BackgroundManager.SCOPE_HOME).themeColor
    } else {
        null
    }
    val set = if (bgTheme != null) base.copy(Accent = Color(bgTheme)) else base
    val scheme = if (dark) darkColorScheme(
        primary = set.Accent, background = set.Background, surface = set.SolidSurface,
        surfaceContainer = set.SolidSurface, surfaceContainerHigh = set.SolidSurface,
        surfaceContainerHighest = set.SolidSurface,
        onPrimary = Color(0xFF062E6F), secondary = set.TextSecondary,
        surfaceVariant = set.SurfaceVariant
    ) else lightColorScheme(
        primary = set.Accent, background = set.Background, surface = set.SolidSurface,
        surfaceContainer = set.SolidSurface, surfaceContainerHigh = set.SolidSurface,
        surfaceContainerHighest = set.SolidSurface,
        onPrimary = Color.White, secondary = set.TextSecondary,
        surfaceVariant = set.SurfaceVariant
    )
    CompositionLocalProvider(
        LocalAppColors provides set,
        // 全局提供更明显的点击涟漪（默认涟漪太淡，感知不到）
        LocalIndication provides ripple(color = set.Accent.copy(alpha = 0.35f))
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** 跟随 [LocalThemeMode] 的常规入口 */
@Composable
fun LinYuTheme(content: @Composable () -> Unit) {
    val themeMode by LocalThemeMode.current
    LinYuThemeSpecific(themeMode, content)
}
