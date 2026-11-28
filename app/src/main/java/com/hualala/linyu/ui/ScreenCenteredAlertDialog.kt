package com.hualala.linyu.ui

import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

internal val ScreenCenteredDialogProperties = DialogProperties(decorFitsSystemWindows = false)

@Composable
@Suppress("DEPRECATION")
internal fun CenterDialogWindowOnScreen() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    DisposableEffect(window) {
        val attrs = window.attributes
        // Use the entire app window for Gravity.CENTER, including the uneven system bars.
        attrs.flags = attrs.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
        attrs.gravity = Gravity.CENTER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attrs.fitInsetsSides = 0
            attrs.fitInsetsTypes = 0
        }
        window.attributes = attrs
        onDispose {}
    }
}

@Composable
internal fun ScreenCenteredAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    title: @Composable () -> Unit,
    text: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        title = {
            CenterDialogWindowOnScreen()
            title()
        },
        text = text,
        dismissButton = dismissButton,
        shape = shape,
        containerColor = containerColor,
        properties = ScreenCenteredDialogProperties
    )
}
