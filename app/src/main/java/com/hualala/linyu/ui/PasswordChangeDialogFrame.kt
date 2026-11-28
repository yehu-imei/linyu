package com.hualala.linyu.ui

import android.graphics.Color
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat

@Composable
@Suppress("DEPRECATION")
internal fun PasswordChangeDialogFrame(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit
) {
    val hostView = LocalView.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogView = LocalView.current
        val window = (dialogView.parent as? DialogWindowProvider)?.window
        SideEffect {
            if (window != null) {
                window.statusBarColor = Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isStatusBarContrastEnforced = false
                }
                ViewCompat.getWindowInsetsController(hostView)?.let { hostBars ->
                    WindowCompat.getInsetsController(window, dialogView).isAppearanceLightStatusBars =
                        hostBars.isAppearanceLightStatusBars
                }
            }
        }
        val captionHeight = with(LocalDensity.current) {
            MaterialTheme.typography.bodyMedium.lineHeight.toDp()
        }
        val smsFormHeight = (outlinedBodyHeight() + outlinedLabelInset() + 10.dp) * 3 +
            captionHeight + 12.dp + 172.dp
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.matchParentSize()
                    .clickable(interactionSource = null, indication = null, onClick = onDismissRequest)
                    .clearAndSetSemantics {}
            )
            Box(
                Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)
            ) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter)
                        .layout { measurable, constraints ->
                            // 用当前 IME 动画帧的可见高度定位，同时保持两种表单的顶部一致。
                            val margin = 8.dp.roundToPx().coerceAtMost(constraints.maxHeight / 2)
                            val top = ((constraints.maxHeight - smsFormHeight.roundToPx()) / 2)
                                .coerceAtLeast(margin)
                            val card = measurable.measure(
                                constraints.copy(minHeight = 0, maxHeight = constraints.maxHeight - top - margin)
                            )
                            layout(card.width, card.height + top + margin) { card.placeRelative(0, top) }
                        }
                        .widthIn(max = 360.dp).fillMaxWidth(),
                    shape = AlertDialogDefaults.shape,
                    color = AlertDialogDefaults.containerColor,
                    tonalElevation = AlertDialogDefaults.TonalElevation
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Box(Modifier.padding(bottom = 16.dp)) {
                            ProvideTextStyle(MaterialTheme.typography.headlineSmall, title)
                        }
                        Box(Modifier.weight(1f, fill = false)) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            dismissButton()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}
