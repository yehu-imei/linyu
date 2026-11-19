package com.hualala.linyu.api

import java.io.IOException
import okhttp3.FormBody
import okhttp3.Request

/** 一次请求的不可变认证快照。切换设备不改变登录默认项目。 */
data class ProjectAuthContext(
    val loginCode: String,
    val userId: String,
    val accountId: String,
    val projectId: String,
    val telephone: String
) {
    val owner: String get() = "$userId:$projectId:$accountId"
    fun fields(): Map<String, String> = mapOf(
        "loginCode" to loginCode, "userId" to userId, "accountId" to accountId,
        "projectId" to projectId, "telephone" to telephone, "telPhone" to telephone,
        "phoneSystem" to "android", "version" to "6.5.28"
    )

    fun forProject(target: String, accounts: Map<String, String>): ProjectAuthContext {
        if (target == projectId) return this
        val account = accounts[target]?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }
            ?: throw ProjectAuthException("未找到该设备的项目账户，请先在官方 App 确认")
        return copy(projectId = target, accountId = account)
    }
}

class ProjectAuthException(message: String) : IOException(message)

data class UserProjectItem(val userId: Long = 0, val projectId: Long = 0, val accountId: Long = 0)

/** 不接受其他用户、无效 ID 或同项目多账户歧义。默认账户始终以登录响应为准。 */
fun projectAccountMap(items: List<UserProjectItem>, session: ProjectAuthContext): Map<String, String> =
    items.filter { (it.userId == 0L || it.userId.toString() == session.userId) && it.projectId > 0 && it.accountId > 0 }
        .groupBy { it.projectId.toString() }
        .mapNotNull { (project, rows) ->
            rows.map { it.accountId.toString() }.distinct().singleOrNull()?.let { project to it }
        }.toMap() + (session.projectId to session.accountId)

/** GET 显式参数只保留一份；POST 的配置头与该次表单一致，不读取可变的“当前设备”。 */
fun authenticateProjectRequest(request: Request, session: ProjectAuthContext): Request {
    val form = request.body as? FormBody
    fun field(name: String): String? = if (request.method == "GET") request.url.queryParameter(name)
        else form?.let { body -> (0 until body.size).firstOrNull { body.name(it) == name }?.let(body::value) }
    val explicitLogin = field("loginCode")
    if (explicitLogin != null && (explicitLogin != session.loginCode || field("userId") != session.userId)) {
        throw ProjectAuthException("登录状态已变化，请重试")
    }
    val target = field("projectId") ?: session.projectId
    if (target != session.projectId && (explicitLogin == null || field("accountId").isNullOrBlank())) {
        throw ProjectAuthException("设备账户信息不完整，请刷新后重试")
    }
    val builder = request.newBuilder()
    if (target.isNotEmpty() && target != "0") {
        builder.header("Config-Project", target)
        builder.header("Config-Keys", "module_list,advertise_type,question_list,service_phone_list," +
            "banner_list_app,activity_list_app,aliCard_popup_config")
    }
    if (request.method == "GET" && session.loginCode.isNotEmpty() && !request.url.encodedPath.contains("verification")) {
        val url = request.url.newBuilder()
        session.fields().forEach { (key, value) -> url.setQueryParameter(key, field(key) ?: value) }
        builder.url(url.build())
    }
    return builder.build()
}
