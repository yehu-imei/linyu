package com.hualala.linyu.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hualala.linyu.data.InstalledPlugins
import com.hualala.linyu.utils.BackgroundManager
import com.hualala.linyu.utils.BackgroundState

/**
 * 全局自定义背景层：绘制在 App 最底层。
 *
 * @param scope [BackgroundManager.SCOPE_HOME]（首页/账单/我的）或 SCOPE_SHOWER（使用页）
 *
 * 只负责「图片 + 透明度 + 模糊 + 亮度」，不参与主题逻辑；
 * 图片经缩放与缓存，效果参数在绘制阶段读取，不会每帧解码。
 */
@Composable
fun AppBackgroundLayer(scope: String) {
    if (InstalledPlugins.BACKGROUND !in installedPluginIds()) return
    val cfg = BackgroundState.config(scope)
    if (!cfg.enabled) return

    val context = LocalContext.current
    // imageVersion 变化（换图）或切换 scope 时才重新取图；loadBitmap 内部还有文件级缓存
    val bitmap = remember(BackgroundState.imageVersion, scope) {
        BackgroundManager.loadBitmap(context, scope)
    } ?: return

    // 亮度：用 ColorMatrix 缩放 RGB 通道（1.0 = 原始亮度）
    val brightnessMatrix = remember(cfg.brightness) {
        ColorMatrix().apply {
            val b = cfg.brightness
            setToScale(b, b, b, 1f)
        }
    }

    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = cfg.opacity }
            .blur(cfg.blur.dp), // Android 12+ 生效，低版本自动无模糊
        contentScale = ContentScale.Crop,
        colorFilter = ColorFilter.colorMatrix(brightnessMatrix)
    )
}
