package com.hualala.linyu.data

import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.api.getProjectListSafe
import com.hualala.linyu.api.switchProjectSafe
import com.hualala.linyu.model.BaseResponse
import com.hualala.linyu.model.ProjectInfo
import com.hualala.linyu.model.ProjectSwitchData
import com.hualala.linyu.utils.PrefsHelper
import kotlinx.coroutines.CancellationException

sealed interface ProjectSwitchOutcome {
    data class Success(val project: ServiceProject) : ProjectSwitchOutcome
    data class Failed(val message: String) : ProjectSwitchOutcome
}

object ProjectRepository {

    suspend fun loadProjects(): BaseResponse<List<ProjectInfo>> =
        NetworkModule.apiService.getProjectListSafe()


    suspend fun switchTo(project: ServiceProject): ProjectSwitchOutcome {
        val target = ServiceProject(
            projectId = project.projectId.trim(),
            projectName = project.projectName.trim()
        )
        if (target.projectId.isBlank() || target.projectId == "0") {
            return ProjectSwitchOutcome.Failed("项目编号无效")
        }

        val previous = NetworkModule.currentProjectId()
        NetworkModule.setProjectId(target.projectId)
        val auth = NetworkModule.authFields()
        return try {
            val resp = NetworkModule.apiService.switchProjectSafe(
                projectId = target.projectId
            )
            if (!resp.success || resp.errorCode != 0) {
                NetworkModule.setProjectId(previous)
                return ProjectSwitchOutcome.Failed(
                    resp.displayMessage ?: "切换项目失败"
                )
            }

            val account = resp.data?.userAccount
            val confirmed = (account?.projectId ?: resp.data?.projectId)?.toString()
                ?.takeIf { it.isNotBlank() && it != "0" }
                ?: target.projectId
            val confirmedName = (account?.projectName ?: resp.data?.projectName)
                ?.takeIf { it.isNotBlank() }
                ?: target.projectName
            val confirmedProject = target.copy(
                projectId = confirmed,
                projectName = confirmedName
            )

            val loginCode = resp.data?.loginCode?.takeIf { it.isNotBlank() }
                ?: resp.data?.v3LoginCode?.takeIf { it.isNotBlank() }
                ?: auth["loginCode"].orEmpty()
            val userId = (account?.userId ?: resp.data?.userId)
                ?.takeIf { it > 0 }?.toString()
                ?: auth["userId"].orEmpty()
            val accountId = (account?.accountId ?: resp.data?.accountId)
                ?.takeIf { it > 0 }?.toString()
                ?: auth["accountId"].orEmpty()
            val telephone = (account?.telephone ?: resp.data?.telephone)
                ?.takeIf { it.isNotBlank() }
                ?: auth["telephone"].orEmpty()

            NetworkModule.updateAuth(
                loginCode = loginCode,
                userId = userId,
                accountId = accountId,
                projectId = confirmed,
                telephone = telephone
            )
            NetworkModule.setProjectId(confirmed)
            PrefsHelper.projectId = confirmed
            PrefsHelper.saveAuth(
                lc = loginCode,
                uid = userId,
                aid = accountId,
                pid = confirmed,
                phone = telephone,
                name = (account?.name ?: resp.data?.name)?.takeIf { it.isNotBlank() }
                    ?: PrefsHelper.userName
            )
            ProjectSwitchOutcome.Success(confirmedProject)
        } catch (e: CancellationException) {
            NetworkModule.setProjectId(previous)
            throw e
        } catch (e: Exception) {
            NetworkModule.setProjectId(previous)
            ProjectSwitchOutcome.Failed(e.message ?: "切换项目时网络异常")
        }
    }
}
