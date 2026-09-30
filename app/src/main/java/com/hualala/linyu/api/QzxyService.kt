package com.hualala.linyu.api

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface QzxyService {

    @FormUrlEncoded
    @POST("/user/login")
    fun login(
        @Field("telephone") telephone: String,
        @Field("password") password: String,
        @Field("phoneSystem") phoneSystem: String = "android",
        @Field("type") type: Int = 0,
        @Field("version") version: String = "6.5.28"
    ): Call<ResponseBody>

    @GET("/account/wallet")
    fun getWallet(): Call<ResponseBody>

    @GET("/device/info/mac")
    fun getDeviceInfo(
        @Query("macAddress") mac: String
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/downRate/rateOrder")
    fun downRate(
        @Field("xfModel") xfModel: Int = 0,
        @Field("snCode") snCode: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/closeOrder")
    fun closeOrder(
        @Field("snCode") snCode: String,
        @Field("orderNo") orderNo: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/query/downRateResult")
    fun downRateResult(
        @Field("snCode") snCode: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/closeOrder/result/query")
    fun closeOrderResult(
        @Field("snCode") snCode: String,
        @Field("orderNo") orderNo: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/consumeOrder/result/query")
    fun consumeOrderResult(
        @Field("snCode") snCode: String,
        @Field("orderNo") orderNo: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/query/rateOrder/using")
    fun queryUsing(
        @Field("xfModel") xfModel: Int = 0,
        @Field("snCode") snCode: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @GET("/order/query/account/bill/list")
    fun getBillList(
        @Query("month") month: String,
        @Query("billRequestType") billRequestType: Int = 2
    ): Call<ResponseBody>

    @GET("/order/query/account/bill/detail")
    fun getBillDetail(
        @Query("orderId") orderId: String,
        @Query("consumeDate") consumeDate: String
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/account/useCode/new/status/update")
    fun updateUseCodeStatus(
        @Field("useCodeStatus") status: Int,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @GET("/account/useCode/new")
    fun getUseCode(): Call<ResponseBody>

    /**
     * 「换一个」使用码。
     *
     * 换出来的码**还没生效**，要再调 [setUseCode] 才作数；3 分钟内不领取就作废。
     * 每天 20 次额度，返回里的 `remainTimes` 是剩余次数。
     */
    @FormUrlEncoded
    @POST("/account/useCode/new/generate")
    fun generateUseCode(
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /**
     * 「确定领取」——把 [generateUseCode] 换出来的码正式生效。
     *
     * 这才是真正改服务端当前使用码的那一步，也是**唯一**会改的一步：
     * 只要不调它，换多少次都不影响手上在用的码。
     */
    @FormUrlEncoded
    @POST("/account/useCode/new/set")
    fun setUseCode(
        @Field("useCode") useCode: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /** 账号信息：姓名 / 学号 / 校园卡绑定状态。GET 会自动带上认证参数 */
    @GET("/account/info")
    fun getAccountInfo(): Call<ResponseBody>

    /**
     * 已绑定卡片的账号信息。
     *
     * 字段是 [/account/info] 的超集（还多了 `lastConsumeTime`、`useCount`、`cardCost`），
     * 姓名和学号都在。
     *
     * ⚠️ 存在的意义是**兜底**：`/account/info` 的 `name` 要学校把学籍数据同步过来才有，
     * 没同步的账号（实测室友的号就是）返回的是 `null`。这两个接口数据来源不同，
     * 一个没有另一个可能有。
     */
    @GET("/account/card/getBindCardInfo")
    fun getBindCardInfo(): Call<ResponseBody>

    /**
     * 一卡通余额与免密支付签约状态。
     *
     * 没签约（signStatus = 0）时服务端不给 amount，调用方要能回退。
     */
    @GET("/settlement/campus/userInfo")
    fun getCampusUserInfo(): Call<ResponseBody>

    /**
     * 更换手机号。
     *
     * [code] 是发到**新手机号**的验证码（`typeId = 5`）。抓包实测：
     * `telephone=新号&typeId=5`，旧号只出现在认证参数 `telPhone` 里——
     * 发到旧号用户根本收不到。
     *
     * 验证码失效时返回 errorCode 29，新号已被注册返回 39。
     */
    @FormUrlEncoded
    @POST("/user/phone/update")
    fun updatePhone(
        @Field("newTelephone") newTelephone: String,
        @Field("code") code: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    // ── 残留账单的手动代扣（v3.0.2）──

    /**
     * 未支付账单列表。
     *
     * 就是俗称的「残留账单」——12 点后结束用水、或者一卡通余额不足的时候，
     * 服务端结算没扣成，账单留在待扣状态。返回的是**扁平结构**（不套 consumeBillDTO）。
     */
    @FormUrlEncoded
    @POST("/order/weixinScorePay/unPay/queryBill")
    fun queryUnpaidBills(@FieldMap auth: Map<String, String>): Call<ResponseBody>

    /**
     * 手动请求代扣。
     *
     * ⚠️ 这个接口和本项目其他 POST **两处都不一样**，照抄别的写法会失败：
     *
     * 1. **body 是 JSON**，其他全是 form-urlencoded。所以这里用 [RequestBody] 而不是 `@FieldMap`
     * 2. **账单用 `consumeDate` 定位**，不是 `orderNo`
     *
     * ⚠️ 这是**动钱**的接口，调用方必须自己做防重（见 `MainViewModel.requestDeduct`）。
     */
    @POST("/order/thirdTrade/single/json/pay")
    fun requestDeduct(@Body body: RequestBody): Call<ResponseBody>

    /** 修改密码。两个密码都是 MD5 取后 10 位大写，和登录用的是同一套 */
    @FormUrlEncoded
    @POST("/user/password/update")
    fun updatePassword(
        @Field("oldPassword") oldPassword: String,
        @Field("password") password: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /**
     * 重置密码（手机验证码方式）——**不需要旧密码**。
     *
     * 这是「没设过密码 / 忘了密码」唯一的出路：`/user/password/update` 必须带
     * `oldPassword`，而 SMS 注册的账号根本没有旧密码可用。
     *
     * 验证码要先走 [getVerificationCode] 且 `typeId = 2` 发到**当前绑定手机号**。
     * [password] 同样是 MD5 取后 10 位大写。
     */
    @FormUrlEncoded
    @POST("/user/password/forget")
    fun forgetPassword(
        @Field("password") password: String,
        @Field("code") code: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /**
     * 项目（学校）信息。`projectName` 就是学校名，用来填个人信息卡片的「学校」，
     * 不用再让用户手输。
     */
    @GET("/project/info/triple")
    fun getProjectInfo(): Call<ResponseBody>

    // ── 短信验证码 ──
    // secret 由 SignUtils.smsSecret(telephone) 按手机号动态计算，不再硬编码
    @GET("/user/verification/code/get")
    fun getVerificationCode(
        @Query("telephone") telephone: String,
        @Query("secret") secret: String,
        @Query("typeId") typeId: Int = 3,
        @Query("platform") platform: Int = 1
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/user/registerAndLogin")
    fun registerAndLogin(
        @Field("telephone") telephone: String,
        @Field("smsCode") smsCode: String,
        @Field("type") type: Int = 5,
        @Field("phoneSystem") phoneSystem: String = "android",
        @Field("version") version: String = "6.5.28"
    ): Call<ResponseBody>

    // ════════════════════════════════════════════
    //  蓝牙水表通道
    //
    //  和上面的 tcpDevice/* 是**两条完全独立的通道**：
    //  · tcpDevice：云端把指令推给设备（要求设备自带 4G）——本项目原有实现
    //  · bluetooth：**手机当网关**，服务端只下发费率包，阀由手机蓝牙写进设备
    //
    //  后者对应的设备在服务端 `isBle` 为真（或 smallTypeId == 1），
    //  对它们调 tcpDevice 必然回 `306 设备不在线`。
    //  详见 docs/蓝牙表适配调研.md
    // ════════════════════════════════════════════

    /**
     * 蓝牙表：下发费率。
     *
     * ⚠️ 响应里的 `downData` 是**要写进设备的费率包原文**（HEX 字符串），
     * 必须原样交给 BLE 层；`consumeDate` 留着失败上报时用。
     *
     * @param macType 由 `type` 和 `a1` 两个字节拼成：`%02x%02x`（见 BleSignBuilder）
     * @param signature 由 [com.hualala.linyu.utils.KlcxkjSigner] 算出
     */
    @FormUrlEncoded
    @POST("/order/downRate/bluetooth/rateOrder")
    fun bluetoothRateOrder(
        @Field("deviceId") deviceId: String,
        @Field("macAddress") macAddress: String,
        @Field("macType") macType: String,
        @Field("bigTypeId") bigTypeId: String,
        @Field("smallTypeId") smallTypeId: String,
        @Field("protocolType") protocolType: String,
        @Field("randomNumber") randomNumber: String,
        @Field("xfModel") xfModel: String,
        @Field("signature") signature: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /**
     * 蓝牙表：上传消费数据（结算）。
     *
     * @param xfData 从设备采集到的消费数据（HEX）
     */
    @FormUrlEncoded
    @POST("/order/upload/bluetooth/data")
    fun bluetoothUploadData(
        @Field("protocolType") protocolType: String,
        @Field("randomNumber") randomNumber: String,
        @Field("xfData") xfData: String,
        @Field("signature") signature: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /**
     * 蓝牙表：上报失败订单。
     *
     * ⚠️ 这个是**必须实现**的，不是可选项：蓝牙开阀没有服务端兜底，
     * 费率包下发后如果没能正常结算（断连、采集失败、用户走远），
     * 设备侧可能停在「开了但没记账」的状态，得靠它告诉服务端这一单作废。
     */
    @FormUrlEncoded
    @POST("/order/upload/bluetooth/fail")
    fun bluetoothFailOrder(
        @Field("consumeDate") consumeDate: String,
        @Field("signature") signature: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    companion object {
        const val BASE_URL = "https://v3-api.china-qzxy.cn"
    }
}
