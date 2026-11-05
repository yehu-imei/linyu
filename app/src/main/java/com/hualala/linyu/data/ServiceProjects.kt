package com.hualala.linyu.data

import com.hualala.linyu.model.ProjectInfo

data class ServiceProject(
    val projectId: String,
    val projectName: String
) {
    val displayName: String get() = projectName.ifBlank { "项目 $projectId" }
}

fun ProjectInfo.toServiceProject(): ServiceProject? {
    val id = projectId?.takeIf { it > 0 } ?: return null
    return ServiceProject(
        projectId = id.toString(),
        projectName = projectName.orEmpty().trim()
    )
}
