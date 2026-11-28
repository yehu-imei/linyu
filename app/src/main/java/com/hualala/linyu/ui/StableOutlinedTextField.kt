package com.hualala.linyu.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun outlinedLabelInset(): Dp = with(LocalDensity.current) { 8.sp.toDp() }

@Composable
internal fun outlinedBodyHeight(): Dp =
    maxOf(56.dp, 32.dp + with(LocalDensity.current) { 24.sp.toDp() })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StableOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val textColor = when {
        !enabled -> colors.disabledTextColor
        focused -> colors.focusedTextColor
        else -> colors.unfocusedTextColor
    }
    val labelInset = outlinedLabelInset()

    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .height(outlinedBodyHeight() + labelInset)
                .semantics(mergeDescendants = true) {}
                .padding(top = labelInset),
            enabled = enabled,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = textColor),
            cursorBrush = SolidColor(colors.cursorColor),
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            decorationBox = { inner ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value,
                    innerTextField = inner,
                    enabled = enabled,
                    singleLine = true,
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                    label = label,
                    leadingIcon = leadingIcon,
                    trailingIcon = trailingIcon,
                    colors = colors,
                    container = {
                        OutlinedTextFieldDefaults.Container(
                            enabled = enabled,
                            isError = false,
                            interactionSource = interactionSource,
                            colors = colors,
                            shape = shape,
                            focusedBorderThickness = 1.dp,
                            unfocusedBorderThickness = 1.dp
                        )
                    }
                )
            }
        )
    }
}
