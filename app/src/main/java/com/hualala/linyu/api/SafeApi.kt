package com.hualala.linyu.api

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hualala.linyu.model.AccountInfo
import com.hualala.linyu.model.BaseResponse
import com.hualala.linyu.model.BillDetail
import com.hualala.linyu.model.BillItem
import com.hualala.linyu.model.CampusUserInfo
import com.hualala.linyu.model.CloseOrderResult
import com.hualala.linyu.model.DownRateResult
import com.hualala.linyu.model.DeviceInfo
import com.hualala.linyu.model.GenerateUseCodeResult
import com.hualala.linyu.model.LoginData
import com.hualala.linyu.model.OrderStatus
import com.hualala.linyu.model.ProjectInfo
import com.hualala.linyu.model.UnpaidBill
import com.hualala.linyu.model.UseCodeData
import com.hualala.linyu.model.WalletData
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val gson = Gson()

private suspend fun Call<ResponseBody>.awaitString(): String {
    return suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { this.cancel() }
        this.enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        cont.resume(body.string())
                    } else {
                        cont.resumeWithException(Exception("Empty response body"))
                    }
                } else {
                    cont.resumeWithException(Exception("HTTP ${response.code()}: ${response.message()}"))
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                cont.resumeWithException(t)
            }
        })
    }
}

private fun <T> parse(json: String, dataClass: Class<T>): BaseResponse<T> {
    val obj = JsonParser().parse(json).asJsonObject
    val success = obj.get("success")?.asBoolean ?: false
    val errorCode = obj.get("errorCode")?.asInt ?: 0
    val errorMessage = obj.get("errorMessage")?.let { if (it.isJsonNull) null else it.asString }
    val msg = obj.get("msg")?.let { if (it.isJsonNull) null else it.asString }
    val dataElement = obj.get("data")
    val data: T? = if (dataElement != null && !dataElement.isJsonNull) {
        gson.fromJson(dataElement, dataClass)
    } else null
    return BaseResponse(success, data, errorCode, errorMessage, msg)
}

private fun <T> parseList(json: String, elementClass: Class<T>): BaseResponse<List<T>> {
    val obj = JsonParser().parse(json).asJsonObject
    val success = obj.get("success")?.asBoolean ?: false
    val errorCode = obj.get("errorCode")?.asInt ?: 0
    val errorMessage = obj.get("errorMessage")?.let { if (it.isJsonNull) null else it.asString }
    val msg = obj.get("msg")?.let { if (it.isJsonNull) null else it.asString }
    val dataElement = obj.get("data")
    val data: List<T>? = if (dataElement != null && !dataElement.isJsonNull && dataElement.isJsonArray) {
        val result = mutableListOf<T>()
        for (item in dataElement.asJsonArray) {
            result.add(gson.fromJson(item, elementClass))
        }
        result
    } else null
    return BaseResponse(success, data, errorCode, errorMessage, msg)
}

suspend fun QzxyService.loginSafe(
    telephone: String,
    password: String,
    phoneSystem: String = "android",
    type: Int = 0,
    version: String = "6.5.24"
): BaseResponse<LoginData> = parse(login(telephone, password, phoneSystem, type, version).awaitString(), LoginData::class.java)

suspend fun QzxyService.getWalletSafe(): BaseResponse<WalletData> =
    parse(getWallet().awaitString(), WalletData::class.java)

suspend fun QzxyService.getDeviceInfoSafe(mac: String): BaseResponse<DeviceInfo> =
    parse(getDeviceInfo(mac).awaitString(), DeviceInfo::class.java)

suspend fun QzxyService.downRateSafe(
    xfModel: Int = 0,
    snCode: String,
    auth: Map<String, String>
): BaseResponse<DownRateResult> = parse(downRate(xfModel, snCode, auth).awaitString(), DownRateResult::class.java)

suspend fun QzxyService.closeOrderSafe(
    snCode: String,
    orderNo: String,
    auth: Map<String, String>
): BaseResponse<Unit> = parse(closeOrder(snCode, orderNo, auth).awaitString(), Unit::class.java)

suspend fun QzxyService.downRateResultSafe(
    snCode: String,
    auth: Map<String, String>
): BaseResponse<DownRateResult> = parse(downRateResult(snCode, auth).awaitString(), DownRateResult::class.java)

suspend fun QzxyService.closeOrderResultSafe(
    snCode: String,
    orderNo: String,
    auth: Map<String, String>
): BaseResponse<CloseOrderResult> = parse(closeOrderResult(snCode, orderNo, auth).awaitString(), CloseOrderResult::class.java)

suspend fun QzxyService.consumeOrderResultSafe(
    snCode: String,
    orderNo: String,
    auth: Map<String, String>
): BaseResponse<CloseOrderResult> = parse(consumeOrderResult(snCode, orderNo, auth).awaitString(), CloseOrderResult::class.java)

/**
 * 同上，但要**原始响应体**。
 *
 * ## 为什么留这条口子
 *
 * 这个接口的响应结构以前一直没抓到过（`API-qzxy.md` 里只有汇总表和 Retrofit 签名，
 * 没有响应样例），当时的解析模型是拿隔壁接口套的、纯猜。
 * 09-18 真机跑通后已经拿到真实结构（见下方 `ConsumeOrderResult`），
 * 但**解析仍然走原始串**，理由有两条：
 *
 * 1. **字段名可能因学校而异**。目前只有一个学校（金华职业技术大学）的样本，
 *    而服务端在别的接口上确实有过按学校改字段名的先例（见 `CloseOrderResult` 的注释）。
 *    按名字找金额比死认一个字段名耐操。
 * 2. **出问题时日志里得有官方原话**。用户报「结算金额不对」，让他导出日志，
 *    原始响应直接摆在那儿，不用再让他去抓包。
 *
 * `parse()` 会把 JSON 吃干抹净只吐 `BaseResponse<T>`，原始串在那一层就丢了，
 * 所以要拿原话就必须从这儿绕过去。
 */
suspend fun QzxyService.consumeOrderResultRaw(
    snCode: String,
    orderNo: String,
    auth: Map<String, String>
): String = consumeOrderResult(snCode, orderNo, auth).awaitString()

suspend fun QzxyService.queryUsingSafe(
    xfModel: Int = 0,
    snCode: String,
    auth: Map<String, String>
): BaseResponse<OrderStatus> = parse(queryUsing(xfModel, snCode, auth).awaitString(), OrderStatus::class.java)

suspend fun QzxyService.getBillListSafe(
    month: String,
    billRequestType: Int = 2
): BaseResponse<List<BillItem>> = parseList(getBillList(month, billRequestType).awaitString(), BillItem::class.java)

suspend fun QzxyService.getBillDetailSafe(
    orderId: String,
    consumeDate: String
): BaseResponse<BillDetail> = parse(getBillDetail(orderId, consumeDate).awaitString(), BillDetail::class.java)

suspend fun QzxyService.updateUseCodeStatusSafe(
    status: Int,
    auth: Map<String, String>
): BaseResponse<Unit> = parse(updateUseCodeStatus(status, auth).awaitString(), Unit::class.java)

suspend fun QzxyService.getUseCodeSafe(): BaseResponse<UseCodeData> =
    parse(getUseCode().awaitString(), UseCodeData::class.java)

suspend fun QzxyService.generateUseCodeSafe(
    auth: Map<String, String>
): BaseResponse<GenerateUseCodeResult> =
    parse(generateUseCode(auth).awaitString(), GenerateUseCodeResult::class.java)

suspend fun QzxyService.setUseCodeSafe(
    useCode: String,
    auth: Map<String, String>
): BaseResponse<Unit> = parse(setUseCode(useCode, auth).awaitString(), Unit::class.java)

suspend fun QzxyService.getVerificationCodeSafe(
    telephone: String,
    secret: String,
    typeId: Int = 3
): BaseResponse<Unit> =
    parse(getVerificationCode(telephone, secret, typeId).awaitString(), Unit::class.java)

suspend fun QzxyService.registerAndLoginSafe(telephone: String, smsCode: String): BaseResponse<LoginData> =
    parse(registerAndLogin(telephone, smsCode).awaitString(), LoginData::class.java)

// ── 账号信息 / 一卡通 ──

suspend fun QzxyService.getAccountInfoSafe(): BaseResponse<AccountInfo> =
    parse(getAccountInfo().awaitString(), AccountInfo::class.java)

/** 字段是 [getAccountInfoSafe] 的超集，复用同一个模型即可（Gson 只映射存在的字段） */
suspend fun QzxyService.getBindCardInfoSafe(): BaseResponse<AccountInfo> =
    parse(getBindCardInfo().awaitString(), AccountInfo::class.java)

suspend fun QzxyService.forgetPasswordSafe(
    password: String,
    code: String,
    auth: Map<String, String>
): BaseResponse<Unit> = parse(forgetPassword(password, code, auth).awaitString(), Unit::class.java)

suspend fun QzxyService.getProjectInfoSafe(): BaseResponse<ProjectInfo> =
    parse(getProjectInfo().awaitString(), ProjectInfo::class.java)

// ── 残留账单的手动代扣 ──

suspend fun QzxyService.queryUnpaidBillsSafe(auth: Map<String, String>): BaseResponse<List<UnpaidBill>> =
    parseList(queryUnpaidBills(auth).awaitString(), UnpaidBill::class.java)

/**
 * 手动请求代扣。
 *
 * body 是 **JSON**（本项目唯一一个），字段固定就那几个，直接拼更省事——
 * 但**值必须转义**，所以走 Gson 而不是字符串模板。
 *
 * ⚠️ `phoneSystem` 传 `WeChat` 是**照抄抓包**的。这个接口是官方 App 里那个 H5
 * 页面调的，和 Android 原生请求（`phoneSystem=android`）不是一条路。
 * 服务端万一校验这个字段，传 android 就会被拒；照抄成功过的那份最稳。
 */
suspend fun QzxyService.requestDeductSafe(
    consumeDate: String,
    auth: Map<String, String>,
    deductType: Int = DEDUCT_TYPE
): BaseResponse<Unit> {
    val body = Gson().toJson(
        mapOf(
            "deductType" to deductType,
            "consumeDate" to consumeDate,
            "projectId" to (auth["projectId"] ?: ""),
            "accountId" to (auth["accountId"] ?: ""),
            "loginCode" to (auth["loginCode"] ?: ""),
            "userId" to (auth["userId"] ?: ""),
            "phoneSystem" to "WeChat"
        )
    )
    return parse(requestDeduct(body.toRequestBody(JSON_MEDIA_TYPE)).awaitString(), Unit::class.java)
}

/**
 * 代扣类型。**只有一个样本，不保证是常量**——万一服务端按扣款渠道变，
 * 会返回错误码而不是扣错钱，改这一处即可。
 * 抓包实测 `{"deductType":7,...}` → `{"success":true}`。
 */
const val DEDUCT_TYPE = 7

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

suspend fun QzxyService.getCampusUserInfoSafe(): BaseResponse<CampusUserInfo> =
    parse(getCampusUserInfo().awaitString(), CampusUserInfo::class.java)

suspend fun QzxyService.updatePhoneSafe(
    newTelephone: String,
    code: String,
    auth: Map<String, String>
): BaseResponse<Unit> =
    parse(updatePhone(newTelephone, code, auth).awaitString(), Unit::class.java)

suspend fun QzxyService.updatePasswordSafe(
    oldPassword: String,
    newPassword: String,
    auth: Map<String, String>
): BaseResponse<Unit> =
    parse(updatePassword(oldPassword, newPassword, auth).awaitString(), Unit::class.java)
