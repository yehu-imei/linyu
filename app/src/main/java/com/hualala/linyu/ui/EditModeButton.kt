package com.hualala.linyu.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.hualala.linyu.R
import com.hualala.linyu.ui.theme.AppColors

@Composable
internal fun EditModeButton(
    editing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(onClick = onClick, modifier = modifier) {
        if (editing) {
            Icon(Icons.Default.Check, "完成编辑", tint = AppColors.Accent, modifier = Modifier.size(20.dp))
        } else {
            Icon(
                painterResource(R.drawable.ic_edit_square),
                "编辑",
                tint = AppColors.TextPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
