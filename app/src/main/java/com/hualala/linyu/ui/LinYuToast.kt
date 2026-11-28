package com.hualala.linyu.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hualala.linyu.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal fun isToastVisible(timerVisible: Boolean, message: String?): Boolean =
    timerVisible && !message.isNullOrEmpty()

internal val LocalToastPageTitleCenter = staticCompositionLocalOf<(Float) -> Unit> { {} }

@Composable
internal fun Modifier.reportToastPageTitleCenter(): Modifier {
    val report = LocalToastPageTitleCenter.current
    return onGloballyPositioned { coordinates ->
        report(coordinates.positionInRoot().y + coordinates.size.height / 2f)
    }
}

@Composable
fun LinYuToast(
    message: String?,
    titleCenterY: () -> Float?,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    val displayMessage = message?.trimEnd()?.let { text ->
        when {
            text.endsWith('。') && !text.endsWith("。。") -> text.dropLast(1).trimEnd()
            text.endsWith('.') && !text.endsWith("..") -> text.dropLast(1).trimEnd()
            else -> text
        }
    }

    LaunchedEffect(message) {
        if (!message.isNullOrEmpty()) {
            visible = true
            delay(2500)
            visible = false
            delay(300)
            onDismiss()
        } else {
            visible = false
        }
    }

    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    val defaultCenter = statusTop + with(density) { 40.dp.toPx() }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment { size, space, _ ->
        val halfHeight = size.height / 2f
        val center = titleCenterY()?.takeIf { it >= statusTop + halfHeight && it <= space.height - halfHeight }
            ?: defaultCenter
        IntOffset((space.width - size.width) / 2, (center - halfHeight).roundToInt())
    }) {
        AnimatedVisibility(
            visible = isToastVisible(visible, displayMessage),
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            Surface(
                modifier = Modifier.padding(horizontal = 24.dp),
                shape = RoundedCornerShape(22.dp),
                color = AppColors.SolidSurface,
                border = BorderStroke(0.8.dp, AppColors.Border),
                shadowElevation = 4.dp
            ) {
                TailEllipsisText(
                    text = displayMessage.orEmpty(),
                    color = AppColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)
                )
            }
        }
    }
}
