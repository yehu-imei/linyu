package com.hualala.linyu.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.hualala.linyu.data.InstalledPlugins

@Composable
internal fun installedPluginIds(): Set<String> {
    val revision by InstalledPlugins.changes.collectAsState()
    return remember(revision) { InstalledPlugins.all.map { it.id }.toSet() }
}
