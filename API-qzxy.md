# 趣智校园第三方客户端开发指南

本文档介绍如何通过趣智校园 API 开发自己的第三方校园热水器客户端。基于淋浴 (LinYu) 项目的逆向工程成果。

---

## 目录

1. [API 概览](#1-api-概览)
2. [认证机制](#2-认证机制)
3. [密码加密](#3-密码加密)
4. [API 接口详解](#4-api-接口详解)
5. [MQTT 实时推送](#5-mqtt-实时推送)
6. [蓝牙设备发现](#6-蓝牙设备发现)
7. [完整业务流程](#7-完整业务流程)
8. [数据模型参考](#8-数据模型参考)
9. [注意事项与踩坑记录](#9-注意事项与踩坑记录)

---

## 1. API 概览

### 基础信息

| 项 | 值 |
|---|---|
| Base URL | `https://v3-api.china-qzxy.cn` |
| 协议 | HTTPS |
| 数据格式 | JSON（GET 请求参数在 URL 中，POST 请求为 form-urlencoded） |
| 认证方式 | 基于 loginCode 的会话认证 |

### 接口列表

| 接口 | 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|---|
| 登录 | POST | `/user/login` | ❌ | 获取 loginCode 等认证信息 |
| 钱包余额 | GET | `/account/wallet` | ✅ | 获取趣智校园钱包余额 |
| 设备信息 | GET | `/device/info/mac` | ✅ | 通过 MAC 地址获取设备详情 |
| 开始洗澡 | POST | `/order/tcpDevice/downRate/rateOrder` | ✅ | 开启热水器 |
| 开阀结果确认 | POST | `/order/tcpDevice/query/downRateResult` | ✅ | 确认开阀是否成功，**`orderNo` 在这里就能拿到**（v1.2.0 新增，v3.0.3 修正）|
| 停止洗澡 | POST | `/order/tcpDevice/closeOrder` | ✅ | 关闭热水器 |
| 关阀结果确认 | POST | `/order/tcpDevice/closeOrder/result/query` | ✅ | 确认关阀是否成功。实测 `data` 恒为 `null`（v1.2.0 新增）|
| 消费结果查询 | POST | `/order/consumeOrder/result/query` | ✅ | 查询消费结算结果。⚠️ `consumeMoney` 单位是**厘**，见 [4.11](#411-消费结果查询) |
| 查询进行中 | POST | `/order/tcpDevice/query/rateOrder/using` | ✅ | 查询设备是否有进行中的订单 |
| 账单列表 | GET | `/order/query/account/bill/list` | ✅ | 获取月度账单 |
| 账单详情 | GET | `/order/query/account/bill/detail` | ✅ | 获取单笔账单详情 |
| 获取使用码 | GET | `/account/useCode/new` | ✅ | 获取当前使用码 + 今日能否重新领取 |
| **换一个**使用码 | POST | `/account/useCode/new/generate` | ✅ | **只换候选码，不生效**，每天 20 次（v3.0.0 修正） |
| **确定领取**使用码 | POST | `/account/useCode/new/set` | ✅ | 把候选码设为当前生效的码（v3.0.0 新增） |
| 使用码开关 | POST | `/account/useCode/new/status/update` | ✅ | 开启/关闭使用码 |
| 发送短信验证码 | GET | `/user/verification/code/get` | ✅ | 发送验证码，secret 由手机号推导（v2.1.0） |
| 短信验证码登录 | POST | `/user/registerAndLogin` | ✅ | 用验证码注册/登录 |
| 账号信息 | GET | `/account/info` | ✅ | 姓名 / 学号 / 校园卡绑定状态（v3.0.0 新增） |
| **一卡通真实余额** | GET | `/settlement/campus/userInfo` | ✅ | 真实校园卡余额，不需破签名（v3.0.0 新增） |
| 一卡通签约状态 | GET | `/settlement/withhold/sign/status` | ✅ | 免密支付是否已签约 |
| 项目信息 | GET | `/project/info/triple` | ✅ | 学校名 `projectName` 在这里（v3.0.0 新增） |
| 更换手机号 | POST | `/user/phone/update` | ✅ | 验证码发到**新**号（v3.0.0 新增） |
| 修改密码 | POST | `/user/password/update` | ✅ | 需要旧密码（v3.0.0 新增） |
| **重置密码** | POST | `/user/password/forget` | ✅ | 只需短信验证码，**不需要旧密码**（v3.0.0 新增） |

---

## 2. 认证机制

登录有**两种方式，返回的字段完全一致**，拿到之后处理方式也一样：

| 方式 | 接口 | 说明 |
|---|---|---|
| 密码登录 | `POST /user/login` | 需要把密码 MD5 后取后 10 位大写（见 [3. 密码加密](#3-密码加密)） |
| 短信验证码登录 | `POST /user/registerAndLogin` | 需要 `secret`，但它由手机号本地推导，**任何手机号可用**。详见 [4.8 短信验证码登录](#48-短信验证码登录) |

### 密码登录流程

```
POST /user/login
Content-Type: application/x-www-form-urlencoded

telephone=13800001111
&password=A1B2C3D4E5          ← MD5 后取后 10 位大写
&phoneSystem=android
&type=0
&version=6.5.24
```

### 登录响应

```json
{
  "success": true,
  "errorCode": 0,
  "errorMessage": "成功",
  "data": {
    "userId": 12345678,
    "telephone": "13800001111",
    "loginCode": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
    "tags": "alLyrelease,999",
    "hasPassword": true,
    "userAccount": {
      "projectId": 999,
      "accountId": 12345,
      "userId": 12345678,
      "name": "张三",
      "accountRealMoney": 0.0
    }
  }
}
```

### 关键字段说明

| 字段 | 说明 | 用途 |
|---|---|---|
| `loginCode` | 会话令牌（32 位十六进制） | 后续所有认证请求都需要此值 |
| `userId` | 用户 ID | 认证参数 |
| `userAccount.accountId` | 账户 ID | 认证参数 |
| `userAccount.projectId` | 项目/学校 ID | 认证参数，不同学校值不同 |
| `userAccount.name` | 用户姓名 | 显示用 |

### 认证参数传递方式

**GET 请求**：认证参数作为 URL Query 参数传递

```
GET /account/wallet?loginCode=xxx&userId=xxx&accountId=xxx&projectId=xxx&telephone=xxx&phoneSystem=android&version=6.5.24
```

**POST 请求**：认证参数作为 Form 字段传递（与业务参数一起）

```
POST /order/tcpDevice/downRate/rateOrder
Content-Type: application/x-www-form-urlencoded

xfModel=0&snCode=xxx&loginCode=xxx&userId=xxx&accountId=xxx&projectId=xxx&telephone=xxx&telPhone=xxx&phoneSystem=android&version=6.5.24
```

### 完整认证参数 Map

```kotlin
fun authFields(): Map<String, String> = mapOf(
    "loginCode" to loginCode,
    "userId" to userId,
    "accountId" to accountId,
    "projectId" to projectId,
    "telephone" to telephone,
    "telPhone" to telephone,      // ⚠️ 两个都要传，值相同
    "phoneSystem" to "android",
    "version" to "6.5.24"
)
```

> ⚠️ `telephone` 和 `telPhone` **两个都必须传**，值相同——这是趣智校园 API 的历史遗留设计。
> **GET 请求也一样**，而且更隐蔽：少传 `telPhone` 时 `/settlement/campus/userInfo`
> 会返回 `errorCode=1 手机号不能为空`，**HTTP 依然是 200**。详见 [9.8](#98-get-请求也要补-telphone)。

### 挤号检测

当 loginCode 失效（在其他设备登录）时，API 会返回包含以下关键词的错误信息：
- "登录"、"token"、"失效"、"过期"、"认证"、"未登录"、"请重新"

HTTP 状态码 401/403 也表示会话失效。

---

## 3. 密码加密

趣智校园使用 **MD5 取后 10 位大写** 作为密码传输格式：

```kotlin
fun encryptPassword(password: String): String {
    val md5 = MessageDigest.getInstance("MD5")
        .digest(password.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return if (md5.length >= 10) {
        md5.substring(md5.length - 10).uppercase()
    } else {
        md5.uppercase()
    }
}
```

**示例**：
- 输入密码：`mypassword`
- MD5 哈希：`34819d7beeabb9260a5c854bc85b3e44`
- 取后 10 位大写：`C854BC85B3`

> ⚠️ 这是趣智校园官方 App 的加密方式，安全性较低，但作为第三方客户端必须遵循。

---

## 4. API 接口详解

### 4.1 钱包余额

```
GET /account/wallet
```

**响应**：
```json
{
  "success": true,
  "data": {
    "accountRealMoney": 0.0,
    "accountGivenMoney": 0.0,
    "money": "0.000"
  }
}
```

| 字段 | 说明 |
|---|---|
| `money` | 总余额（字符串格式） |
| `accountRealMoney` | 真实充值金额 |
| `accountGivenMoney` | 赠送金额 |

### 4.2 设备信息

```
GET /device/info/mac?macAddress=AA:BB:CC:DD:EE:FF
```

**响应**：
```json
{
  "success": true,
  "data": {
    "deviceId": 18224,
    "deviceName": "热水器-学生公寓-1号楼-3层-301",
    "snCode": "QZXY20230001",
    "macAddress": "AA:BB:CC:DD:EE:FF",
    "withholdMoney": 2.0,
    "onlineStatusId": 1
  }
}
```

| 字段 | 说明 |
|---|---|
| `deviceId` | 设备 ID |
| `deviceName` | 设备名称（包含位置信息） |
| `snCode` | 设备序列号（控制设备的关键标识） |
| `macAddress` | MAC 地址 |
| `withholdMoney` | 预扣金额（元） |
| `onlineStatusId` | 在线状态 |

**设备名称格式**：`热水器-{校区}-{楼栋}-{楼层}-{房间}` 或 `洗手台{编号}-{校区}-{楼栋}-{楼层}-{房间}`

### 4.3 开始洗澡

```
POST /order/tcpDevice/downRate/rateOrder
Content-Type: application/x-www-form-urlencoded

xfModel=0&snCode=QZXY20230001&loginCode=xxx&userId=xxx&...
```

| 参数 | 说明 |
|---|---|
| `xfModel` | 消费模式，固定传 `0` |
| `snCode` | 设备序列号 |
| 认证参数 | 见认证机制 |

**响应**：
```json
{
  "success": true,
  "errorCode": 0,
  "errorMessage": "成功",
  "data": null
}
```

> ⚠️ **`downRate` 本身确实不返回 orderNo，但紧接着的 `downRateResult` 会返回——不用等 MQTT，也不用轮询 `queryUsing`。**
>
> 这条结论以前写错了（说是"需要通过 MQTT 推送或轮询 `queryUsing` 获取"），
> 导致客户端多绕了一圈：拿到 orderNo 只用来判断"开没开"，落盘时写空串，
> 再靠一个独立的轮询任务事后补上。那个轮询要 11 轮 × 800ms，
> **"开完水马上停"时还没跑出结果，本地就是空的**——而关阀、结算这几个接口全都要 orderNo。
>
> 实测（09-18 15:06:28，见 4.3.1）：
>
> ```json
> {"success":true,"errorCode":0,"errorMessage":"成功","data":{
>   "deviceSnCode":"C47F0EDCBCC7",
>   "consumeDate":"20260918150627",
>   "orderNo":"13202609181506275230",
>   "preDeductMoney":0,"accountType":2,"consumeSceneType":4,
>   "state":1,"result":0,"createTime":"1789715217", ...}}
> ```

### 4.3.1 开阀结果确认

```
POST /order/tcpDevice/query/downRateResult
Content-Type: application/x-www-form-urlencoded

snCode=QZXY20230001&loginCode=xxx&userId=xxx&...
```

**响应（已确认开阀）**：

> ⚠️ 下面这份是**原样摘录**，不是整理过的样例——日志按行长截断，所以
> `liquidOrderStatusD...` 后面还有内容没记下来。里面**没有** `autoDisConTime`，
> 不等于服务端不返回它（见下方字段表）。

```json
{
  "success": true,
  "errorCode": 0,
  "errorMessage": "成功",
  "data": {
    "projectId": null,
    "accountId": 41681,
    "deviceSnCode": "C47F0EDCBCC7",
    "consumeDate": "20260918150655",
    "orderNo": "13202609181506558872",
    "preDeductMoney": 0,
    "accountType": 2,
    "consumeSceneType": 4,
    "xfModelName": null,
    "state": 1,
    "result": 0,
    "createTime": "1789715217",
    "liquidOrderNo": null,
    "liquidOrderStatus": null
  }
}
```

**响应（还没开好，要继续轮询）**：

```json
{"success":true,"errorCode":1,"errorMessage":"网络通讯慢,请稍后再试","data":null}
```

⚠️ 注意 `errorCode: 1` 时 **HTTP 仍然是 200**，不能只判 HTTP 状态码。

| 字段 | 说明 |
|---|---|
| `orderNo` | **开阀确认后这里就有**，不用等 MQTT / `queryUsing` |
| `state` / `result` | 实测成功时是 `state=1, result=0`——**`state` 不是 0**，所以判断不能只看 `state == 0`，要两个都看 |
| `preDeductMoney` | 预扣金额。**单位见 4.11** |
| `consumeDate` | 紧凑格式 `yyyyMMddHHmmss`，和账单接口的 `yyyy-MM-dd HH:mm:ss` 不一样 |
| `createTime` | 服务端当前时间（Unix 秒），**不是**下单时间（每次请求都在变） |
| `autoDisConTime` | 自动关停秒数，客户端用 `DownRateResult.autoDisConTime` 读它显示倒计时。⚠️ **本次抓到的日志里这一段被截断了，没有真正看到这个字段**，字段名是从客户端已有的行为反推的——如果你的学校拿不到倒计时，先来这里核对 |

### 4.4 停止洗澡

```
POST /order/tcpDevice/closeOrder
Content-Type: application/x-www-form-urlencoded

snCode=QZXY20230001&orderNo=1234567&loginCode=xxx&userId=xxx&...
```

| 参数 | 说明 |
|---|---|
| `snCode` | 设备序列号 |
| `orderNo` | 订单号（从 queryUsing 或 MQTT 获取） |
| 认证参数 | 见认证机制 |

### 4.5 查询进行中的订单

```
POST /order/tcpDevice/query/rateOrder/using
Content-Type: application/x-www-form-urlencoded

xfModel=0&snCode=QZXY20230001&loginCode=xxx&userId=xxx&...
```

**响应（有进行中订单）**：
```json
{
  "success": true,
  "errorCode": 0,
  "data": {
    "orderNo": "1234567",
    "state": 1,
    "snCode": "QZXY20230001",
    "isOwner": true
  }
}
```

**响应（无进行中订单）**：
```json
{
  "success": true,
  "errorCode": 0,
  "data": {
    "orderNo": null,
    "isOwner": true
  }
}
```

**特殊 errorCode**：
- `307`：表示设备正在使用中（即使 `success` 为 false）

| 字段 | 说明 |
|---|---|
| `orderNo` | 订单号（null 表示无进行中订单） |
| `isOwner` | 是否为当前用户发起的订单 |
| `errorCode: 307` | 设备正在使用中 |

### 4.6 账单列表

```
GET /order/query/account/bill/list?month=2026-05&billRequestType=2
```

| 参数 | 说明 |
|---|---|
| `month` | 月份，格式 `yyyy-MM` |
| `billRequestType` | 账单类型，固定传 `2` |

**响应**：
```json
{
  "success": true,
  "data": [
    {
      "consumeBillDTO": {
        "orderId": "1234567",
        "consumeDate": "2026-05-30 10:08:42",
        "consumeMoney": "0.08",
        "description": "热水器:学生公寓-1号楼-3层-301洗手台"
      }
    }
  ]
}
```

### 4.7 使用码

使用码是给热水器**物理键盘**用的：不想掏手机开阀时，在设备上直接输 8 位码。
一共**三个**接口，分工很容易搞混——v3.0.0 之前一直以为是两个。

#### 三个接口的分工

| 接口 | 干什么 | 会不会改当前生效的码 |
|---|---|---|
| `GET /account/useCode/new` | 看当前码 + 今日额度 | 不会 |
| `POST .../generate` | **换一个**（候选码） | **不会** |
| `POST .../set` | **确定领取** | **会**，且只有它会 |

> **这是 v3.0.0 抓包才确认的关键一点**：连换 7 次 `generate` 期间，
> 服务端生效的码一直没变，直到 `set` 那一下 `useCodeStartTime` 才被改写。
> 所以完全可以做成「先预览、再决定领不领」，**取消是零成本的**。

#### 获取当前使用码

```
GET /account/useCode/new
```

```json
{
  "success": true,
  "data": {
    "useCode": "27389960",
    "useCodeStatus": 1,
    "useCodeRandom": "960",
    "useCodeStartTime": "2026-09-17 18:12:05",
    "useCodeExpiringTime": null,
    "resetAvailability": 0,
    "resetAvailabilityWarMark": "1天只能领取一次使用码"
  }
}
```

| 字段 | 说明 |
|---|---|
| `useCode` | 使用码（8 位数字） |
| `useCodeStatus` | `1` = 已开启，`0` = 已关闭 |
| `useCodeRandom` | 后三位，与手机号后三位相同 |
| `useCodeStartTime` | 当前码的生效时间，**每次 `set` 都会被改写** |
| `resetAvailability` | 今天还能不能**重新领取**：`1` = 能，`0` = 不能 |
| `resetAvailabilityWarMark` | `resetAvailability` 为 0 时的原因文案 |

> ⚠️ **没领过使用码的用户，`useCode` 返回的是 `null`**（不是空串也不是 0）。
> 客户端要能把「还没拉到」和「服务端说这人没有码」区分开，
> 否则没领过码的人会永远卡在「加载中」。

#### 换一个（不生效）

```
POST /account/useCode/new/generate
Content-Type: application/x-www-form-urlencoded

loginCode=xxx&userId=xxx&accountId=xxx&projectId=xxx&
telephone=xxx&telPhone=xxx&phoneSystem=android&version=6.5.28
```

```json
{"success": true, "data": {"useCode": "84663364", "remainTimes": 15}}
```

| 字段 | 说明 |
|---|---|
| `useCode` | 换出来的**候选码**，未生效 |
| `remainTimes` | **剩余可换次数**，每天 20 次，每换一次减 1 |

实测一次连续换码的 `remainTimes` 变化（本次会话共换 7 次）：

```
19 → 18 → 17 → 16 → 15 → 14 → 13
```

即从 20 起算，每调一次扣 1。

> `remainTimes` 和上面 `GET` 里的 `resetAvailability` **不是同一个东西**：
> 前者是「今天还能换几次」（20 次/天），后者是「今天还能不能**领取**」（1 次/天）。

#### 确定领取（唯一生效的一步）

```
POST /account/useCode/new/set
Content-Type: application/x-www-form-urlencoded

useCode=00740364&loginCode=xxx&userId=xxx&...
```

成功返回 `{"success": true, "data": null}`，此后 `GET` 到的 `useCodeStartTime` 会变成这次调用的时间。

> **换出来的码有 3 分钟领取时限**，超时不 `set` 就作废（官方 App 的行为）。
> 客户端应该在预览界面挂个倒计时，别让用户挑了半天再点确定却已经被服务端丢掉了。

#### 开关使用码

```
POST /account/useCode/new/status/update
Content-Type: application/x-www-form-urlencoded

useCodeStatus=1&loginCode=xxx&userId=xxx&...
```

| 参数 | 说明 |
|---|---|
| `useCodeStatus` | `1` = 开启，`0` = 关闭 |

> 这个接口**也会**改写 `useCodeStartTime`。

---

### 4.8 短信验证码登录

v2.1.0 起可用，**任何手机号都能用，不需要抓包**。关键在于 `secret` 参数并不是随机值，
而是完全由手机号推导出来的（见下方）。

#### 发送验证码

```
GET /user/verification/code/get?telephone={手机号}&typeId=3&platform=1&secret={secret}
```

| 参数 | 值 | 说明 |
|---|---|---|
| `telephone` | 11 位手机号 | |
| `typeId` | `3` | 固定值 |
| `platform` | `1` | 固定值 |
| `secret` | 见下方算法 | 官方 App 发验证码时带的签名 |

#### secret 算法（本项目的核心发现）

抓包时 `secret` 看起来像是每台设备各不相同的随机值，**实际上它只跟手机号有关**：

```
secret = MD5( 手机号前3位 + 手机号后4位 + "klcx" )      // 32 位小写十六进制
```

以 `18582613960` 为例：

```
MD5("185" + "3960" + "klcx")
 = MD5("1853960klcx")
 = e3d2220b920cca13499ea76328e24a4e
```

因为算法里不含任何设备侧密钥，**任何人、任何手机号都能在本地算出自己的 secret**，
所以短信登录可以通用实现。本项目实现见 `utils/SignUtils.kt`。

> 这也说明 `secret` 不是身份凭据——真正的身份校验发生在下一步提交验证码的时候。

#### 提交验证码登录

```
POST /user/registerAndLogin
Content-Type: application/x-www-form-urlencoded

telephone={手机号}&smsCode={验证码}&type=5&phoneSystem=android&version=6.5.24
```

| 参数 | 值 | 说明 |
|---|---|---|
| `smsCode` | 6 位数字 | 收到的短信验证码 |
| `type` | `5` | 固定值 |
| `phoneSystem` | `android` | |
| `version` | `6.5.24` | 与密码登录保持一致 |

> ⚠️ 这个接口**不需要** `loginCode` / `userId` 等认证参数——它本身就是用来换取这些的。

**响应**：与密码登录完全一致（`loginCode` / `userId` / `userAccount` 等），
拿到后按同样的方式保存即可，详见 [2. 认证机制](#2-认证机制)。

#### 与密码登录的差别

| | 密码登录 | 短信登录 |
|---|---|---|
| 接口 | `POST /user/login` | `POST /user/registerAndLogin` |
| 需要 secret | 否 | 是（按手机号本地计算） |
| 返回字段 | 完全一致 | 完全一致 |

---

### 4.9 账号信息与一卡通

v3.0.0 新增。**一卡通余额不再需要破签名**——趣智校园把易校园的接口代理了。

#### 账号信息

```
GET /account/info
```

```json
{
  "success": true,
  "data": {
    "projectId": 905,
    "accountId": 41681,
    "userId": 18981460,
    "telephone": "19182692082",
    "name": "郑豪",
    "genderName": "未知",
    "idCardNumber": "202410101080040",
    "isCard": 0,
    "cardStatus": -1,
    "cardStatusName": "未绑定"
  }
}
```

| 字段 | 说明 |
|---|---|
| `name` | 姓名。**学校没同步时会是 `null`** |
| `idCardNumber` | **学号**（不是身份证号） |
| `cardStatus` / `cardStatusName` | 校园卡绑定状态，和下面的免密签约是两回事 |

#### 一卡通真实余额

```
GET /settlement/campus/userInfo?projectId=905&telPhone={手机号}&userId=xxx&accountId=xxx&telephone={手机号}&...
```

```json
{
  "success": true,
  "data": {
    "studentNumber": "202410101080040",
    "studentName": "郑豪",
    "amount": "16.76",
    "signStatus": 1
  }
}
```

| 字段 | 说明 |
|---|---|
| `amount` | **一卡通余额，字符串**（要自己转 Double） |
| `signStatus` | `1` = 已签约免密支付，`0` = 未签约 |
| `studentNumber` / `studentName` | 和 `/account/info` 的学号、姓名一致 |

> ⚠️⚠️ **两个必须知道的坑：**
>
> **1. 这个接口出错也返回 HTTP 200。** 错误在 body 里：
> ```json
> {"success": false, "errorCode": 1, "errorMessage": "手机号不能为空", "data": null}
> ```
> 只看状态码会以为一切正常，然后 `data` 是 null、静默回退到估算余额，
> 表现成「功能没生效」。判断成功必须看 `success` 字段。
>
> **2. 它要的是 `telPhone`，不是 `telephone`。** 绝大多数接口只认 `telephone`，
> 这个偏偏要 `telPhone`（两个都带最保险）。少传就报上面那个「手机号不能为空」。
> 如果用拦截器统一补 GET 参数，**两个字段都要补**，见 9.2。

> 未签约（`signStatus == 0`）时服务端不给 `amount`，客户端只能回退到
> 「初始余额 − 账单消费」的本地估算。

#### 签约状态

```
GET /settlement/withhold/sign/status
```

返回是否已签约代扣。签约/解约对应：
`POST /settlement/campus/agreement/open`（需要 `campusAccount` + `campusPassword`，即学校统一身份认证账密）、
`POST /settlement/campus/agreement/close`。

#### 学校名

```
GET /project/info/triple
```

```json
{"success": true, "data": {"projectId": 905, "projectName": "金华职业技术大学", ...}}
```

`projectName` 就是学校名，`projectDescription` 是学院名。

---

### 4.10 手机号与密码

v3.0.0 新增。**验证码的 `typeId` 决定用途**，用错就收不到码。

| `typeId` | 用途 | `telephone` 填哪个号 | 需要登录态 |
|---|---|---|---|
| `2` | **重置/修改密码** | 当前绑定号 | ✅ |
| `3` | 登录 / 注册 | 要登录的号 | ❌ |
| `5` | **更换手机号** | **新**号 | ✅ |

#### 更换手机号

```
# 1. 发验证码（注意 telephone 是新号！）
GET /user/verification/code/get?typeId=5&telephone={新号}&secret={secret}&...

# 2. 提交
POST /user/phone/update
Content-Type: application/x-www-form-urlencoded

newTelephone=19182692082&code=396948&loginCode=xxx&telephone={旧号}&telPhone={旧号}&...
```

> ⚠️ 验证码发到**新**号，旧号只出现在认证参数（`telephone` / `telPhone`）里。
> 写反了的话用户永远收不到码。这是 v3.0.0 修掉的一个真 bug。

错误码：`29` = 验证码失效，`39` = 新手机号已被其他账号绑定。

#### 修改密码（知道旧密码）

```
POST /user/password/update
Content-Type: application/x-www-form-urlencoded

oldPassword=A98BF854DD&password=16BAC22278&loginCode=xxx&...
```

两个密码都是 **MD5 取后 10 位大写**（见第 3 节），和登录用的是同一套。

#### 重置密码（不知道旧密码）

**这是用验证码注册的账号唯一的出路**——`/user/password/update` 必须带 `oldPassword`，
而短信注册的账号根本没有旧密码可填。

```
# 1. 发验证码（typeId=2，发到当前绑定号）
GET /user/verification/code/get?typeId=2&telephone={当前号}&secret={secret}&...

# 2. 重置
POST /user/password/forget
Content-Type: application/x-www-form-urlencoded

password=0AB7065F1C&code=198871&loginCode=xxx&...
```

**不需要 `oldPassword`。** 新密码同样是 MD5 取后 10 位大写。

### 4.11 消费结果查询

```
POST /order/consumeOrder/result/query
Content-Type: application/x-www-form-urlencoded

snCode=QZXY20230001&orderNo=13202609181506558872&loginCode=xxx&userId=xxx&...
```

**响应**：

```json
{
  "success": true,
  "errorCode": 0,
  "errorMessage": "成功",
  "data": {
    "consumeTime": "2026-09-18 15:06:27",
    "consumeDate": "2026-09-18 15:06:27",
    "consumeMoney": 0,
    "preDeductMoney": 0,
    "preDeductMoneyAfter": 0,
    "orderNo": "13202609181506275230",
    "deviceSnCode": "C47F0EDCBCC7",
    "orderAccountId": 41681,
    "createTime": "1789715194",
    "modeName": null,
    "liquidModeName": null,
    "liquidConsumeMoney": null,
    "leftModeMoney": null,
    "rightModeMoney": null,
    "leftModeName": null,
    "rightModeName": null,
    "clData": null,
    "telephone": "1xxxxxxxxxx"
  }
}
```

#### ⚠️ `consumeMoney` 的单位是**厘**，不是元

**这是这个接口最容易踩的坑。** 它和账单列表里**同名字段**的单位差 1000 倍：

| 接口 | 字段 | 类型 | 同一笔 0.04 元的账返回 |
|---|---|---|---|
| `consumeOrder/result/query` | `consumeMoney` | **数字** | `40` |
| `query/account/bill/list` | `consumeMoney` | **字符串** | `"0.04"` |

字段名一模一样、类型一个数字一个字符串、单位还差 1000 倍。忘了换算的后果不是报错，
而是**静默错 1000 倍**——用了 0.04 元，通知上写「消费 ¥40.00」。

实测证据（09-18 两笔独立订单，用 `dealDate` 对齐同一笔账）：

| `dealDate` | 本接口 | 账单列表 |
|---|---|---|
| `2026-09-18 17:16:28` | `40` | `"0.04"` |
| `2026-09-18 17:16:56` | `80` | `"0.08"` |

#### 为什么该用它，而不是翻账单列表

账单列表里**同名字段**是元，看着更省事，但它**赶不上**结算通知的时间窗：

- 账单是「先以占位的 `consumeMoney: "0.0"` 出现，`uploadDate` 为空，结算完成后才填金额」。
  实测同一笔账：`12:47:36` 是 `"0.0"`、`uploadDate` 空；`12:47:37` 变成 `"0.41"`、`uploadDate` 填上。
- 所以「在账单里查到这一单」≠「金额已经算好了」，拿它当准绳反而可能把真金额覆盖成 0。

这个接口是直接问结算结果，答案**一次就给全**。

#### 什么时候能拿到金额

实测 27 次调用，**第 1 轮的返回就是最终值，从来没有变过**：

- 有消费的单子，关阀后第一次查就返回真实金额（`consumeMoney` 为正）
- 没消费的单子（开完水马上停），第 1 轮就是 `consumeMoney: 0`，
  **且账单列表里始终没有这一单的账单**——两边对得上，这个 0 是真的

所以不需要像轮询账单那样重试很多轮：查一次，是 0 就再确认一次，够用了。

| 字段 | 说明 |
|---|---|
| `consumeMoney` | 消费金额，**单位：厘**（1 元 = 1000 厘）|
| `consumeTime` / `consumeDate` | 都是**下单/结算时刻**（`yyyy-MM-dd HH:mm:ss`），和 `downRateResult` 里紧凑格式的同名字段不是一回事 |
| `orderNo` | 就是请求时传进来的那个 |
| `createTime` | 服务端当前时间（Unix 秒），**每次请求都在变**，不是下单时间 |
| `preDeductMoneyAfter` | 结算后剩余的预扣额 |

> ⚠️ 响应里**没有** `consumeMoneyStr`，也**没有** `state` / `result` / `status`
> ——那几个是 `downRateResult` 的字段，别混。客户端早期版本照隔壁接口的模型解析这个响应，
> 属于没样本时的猜测。

---

## 5. MQTT 实时推送

### 连接信息

| 项 | 值 |
|---|---|
| 服务器 | `tcp://47.107.37.60:1883` |
| 协议 | MQTT 3.1.1（明文 TCP，无 TLS） |
| 认证 | 无（匿名连接） |
| ClientId | 随机生成 |

### 订阅主题

开始洗澡后，需要订阅以下三个主题（`{phone}` 替换为用户手机号）：

| 主题 | 说明 |
|---|---|
| `app_downRate_{phone}` | 开始洗澡事件推送 |
| `app_shutdownOrder_{phone}` | 停止洗澡事件推送 |
| `app_uploadData_{phone}` | 实时消费金额推送 |

### 消息格式

```json
{
  "orderNo": "1234567",
  "consumeMoney": 0.08,
  "state": 1,
  "result": 0
}
```

| 字段 | 说明 |
|---|---|
| `orderNo` | 订单号（开始洗澡后通过此字段获取） |
| `consumeMoney` | 当前已消费金额（元） |
| `state` | 订单状态 |
| `result` | 操作结果 |

### 使用场景

1. **获取 orderNo**：调用 `downRate` 后，MQTT 会推送包含 `orderNo` 的消息
2. **实时更新余额**：`app_uploadData` 主题会推送 `consumeMoney` 更新
3. **HTTP 轮询兜底**：如果 MQTT 连接失败，应使用 HTTP 轮询 `queryUsing` 作为备选方案

---

## 6. 蓝牙设备发现

### BLE 扫描

趣智校园热水器通过 BLE（低功耗蓝牙）广播设备信息。

**设备名称过滤**：包含 `KLCXKJ-Water`（不区分大小写）

```kotlin
// Android BLE 扫描示例
val scanner = bluetoothAdapter.bluetoothLeScanner
val settings = ScanSettings.Builder()
    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
    .build()

scanner.startScan(null, settings, object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
        val name = result.device.name ?: return
        if (name.contains("KLCXKJ-Water", ignoreCase = true)) {
            // 发现热水器设备
            val mac = result.device.address   // MAC 地址
            val rssi = result.rssi            // 信号强度
        }
    }
})
```

### 信号强度参考

| RSSI 范围 | 信号等级 |
|---|---|
| ≥ -70 dBm | 强 |
| -70 ~ -85 dBm | 中 |
| < -85 dBm | 弱 |

### 设备发现流程

```
1. BLE 扫描 → 获取 MAC 地址
2. 调用 GET /device/info/mac?macAddress=xxx → 获取设备详情（snCode、名称等）
3. 调用 POST /order/tcpDevice/query/rateOrder/using → 检查是否有进行中订单
```

---

## 7. 完整业务流程

### 洗澡流程

> ⚠️ **v3.0.0 起，订单状态的轮询和超时关停由前台服务（`ShowerWatchService`）负责**，
> 不再是界面里的定时器。这是关键区别：以前退出使用页或被划掉，倒计时就停摆，
> 超时了也没人管。现在监控跟界面彻底解耦。

```
用户打开 App
    │
    ├── BLE 扫描附近设备
    │       │
    │       └── 获取 MAC → 查询设备信息 → 获取 snCode
    │
    ├── 选择设备 → 显示设备详情弹窗
    │       │
    │       ├── 查询 queryUsing → 检查是否已有进行中订单
    │       │       │
    │       │       ├── 有订单 + isOwner=true  → 直接进入洗澡中界面（恢复订单，不会重新开阀）
    │       │       ├── 有订单 + isOwner=false → 拒绝，提示「他人使用中」
    │       │       │
    │       │       └── 无订单 → 调用 downRate 开始洗澡
    │       │               │
    │       │               ├── 连接 MQTT 订阅推送
    │       │               ├── 进入洗澡中界面
    │       │               └── 轮询 queryUsing 获取 orderNo（最多 10 次，间隔 800ms）
    │       │
    │       ├── 启动前台服务 ShowerWatchService
    │       │       │
    │       │       ├── 挂常驻通知（带「结束用水」按钮）
    │       │       ├── 每 15 秒查询一次订单还在不在
    │       │       │     剩 ≤14 秒时改成精确等到超时那一刻
    │       │       ├── 查到订单没了 → 关阀 + 结算 + 发结束通知
    │       │       └── ⚠️ 网络失败时**什么都不做**，下一轮再说，
    │       │             绝不因为一次抖动就误判成「已结束」
    │       │
    │       └── 洗澡中界面
    │               │
    │               ├── 显示计时器（每秒刷新，起点是持久化的开阀时间戳）
    │               ├── 显示预扣金额
    │               └── MQTT 推送更新消费金额
    │
    ├── 用户点「结束使用」（界面 / 通知栏 / 桌面小组件，三个入口）
    │       │
    │       ├── 界面入口 → 通知服务
    │       ├── 通知 / 小组件入口 → 直接走服务
    │       │
    │       └── 服务统一执行：挂「正在结束…」→ closeOrder 轮询确认关阀
    │              → 查账单结算金额 → 发「使用结束」通知 → 撤通知、停服务
    │
    └── 使用码启动的设备（物理键盘操作）
            │
            └── 刷新时 queryUsing 发现进行中订单 → 自动添加到活跃列表
```

### 登录流程

**两种方式，返回的字段完全一致**，拿到之后处理方式也一样：

```
方式 A：手机号 + 密码（用户自己设过密码）
    │
    ├── 密码 MD5 加密（取后 10 位大写）
    ├── POST /user/login
    └── 成功 → 保存 loginCode / userId / accountId / projectId → 进主页

方式 B：手机号 + 短信验证码（v2.1.0 起，**App 默认用这个**）
    │
    ├── secret = MD5(手机号前3位 + 后4位 + "klcx")   ← 本地算，任何人可用
    ├── GET /user/verification/code/get?typeId=3&telephone=xxx&secret=xxx
    ├── POST /user/registerAndLogin   ← 没注册过的手机号**会自动注册**
    └── 成功 → 同上

自动登录：
    └── 从 EncryptedSharedPreferences 恢复认证信息 → 进主页
```

> 密码登录有个先天限制：**用验证码注册的账号根本没有密码**，所以 App 默认走验证码。
> 忘了密码也有出路，见 [4.10](#410-手机号与密码) 的重置密码分支。
>
> ⚠️ 两种方式都必须处理**挤号**：同一账号不能在多设备同时在线，被挤下去时
> 服务端返回含「登录 / token / 失效 / 过期」字样的错误，或 HTTP 401/403。

短信验证码登录（替代上面「输密码 + MD5 + /user/login」这三步）：

```
用户输入手机号
    │
    ├── 本地算 secret = MD5(前3位 + 后4位 + "klcx")     ← 不需要抓包
    │
    ├── GET /user/verification/code/get?telephone=...&typeId=3&platform=1&secret=...
    │       └── 服务器下发短信验证码
    │
    ├── 用户输入收到的验证码
    │
    └── POST /user/registerAndLogin  (telephone + smsCode + type=5)
            │
            └── 成功 → 返回字段与密码登录完全一致
                        → 同样保存 loginCode / userId / accountId / projectId
```

---

## 8. 数据模型参考

### 统一响应格式

所有 API 返回统一的 JSON 结构：

```json
{
  "success": true,
  "errorCode": 0,
  "errorMessage": "成功",
  "data": { ... }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `success` | Boolean | 请求是否成功 |
| `errorCode` | Int | 错误码，0 表示成功 |
| `errorMessage` | String? | 错误信息 |
| `msg` | String? | 备用错误信息字段 |
| `data` | T? | 业务数据（类型因接口而异） |

### 特殊 errorCode

| errorCode | 说明 |
|---|---|
| `0` | 成功 |
| `1` | 参数缺失（如 `手机号不能为空`），**HTTP 仍是 200** |
| `12` | 手机号或密码错误 |
| `29` | 验证码已失效 |
| `39` | 该手机号已绑定其他账号 |
| `46` | 验证码错误 |
| `53` | 请勿频繁获取验证码 |
| `307` | 设备正在使用中 |
| `4004` | 设备不存在 |

---

## 9. 注意事项与踩坑记录

### 9.1 R8 混淆与 Gson 泛型

如果使用 Kotlin + R8 full mode + Retrofit suspend 函数，R8 会擦除 suspend 函数的泛型签名，导致 Gson 无法解析 `BaseResponse<T>` 的类型参数。

**解决方案**：
- Retrofit 接口返回 `Call<ResponseBody>`（非 suspend）
- 用 `suspendCancellableCoroutine` 桥接回调到协程
- 手动用 `JsonParser` 解析 JSON，用 `Class<T>` 反序列化 data 字段

### 9.2 POST 请求的双重 telephone 字段

POST 请求的认证参数中，`telephone` 和 `telPhone` 两个字段都需要传，值相同。遗漏任一字段可能导致认证失败。

> **GET 请求有同样的问题**，而且更隐蔽——见 [9.8](#98-get-请求也要补-telphone)。

### 9.3 orderNo 的异步获取

调用 `downRate` 后不会立即返回 `orderNo`。需要：
1. 连接 MQTT 等待推送（优先）
2. 或轮询 `queryUsing` 接口（兜底，建议间隔 800ms，最多 10 次）

### 9.4 MQTT 连接失败的处理

MQTT 连接可能因网络原因失败。应实现 HTTP 轮询兜底方案，确保即使 MQTT 不可用也能正常控制设备。

### 9.5 使用码启动设备的检测

用户可能在热水器物理键盘上输入使用码启动设备，此时 App 并不知道。需要在刷新设备列表时，对每个发现的设备调用 `queryUsing` 检查是否有进行中的订单。

### 9.6 挤号检测

趣智校园不支持多设备同时在线。当用户在另一台设备登录时，当前设备的 loginCode 会失效。API 会返回包含"登录"、"token"、"失效"等关键词的错误信息，或返回 HTTP 401/403。

### 9.7 一卡通余额（v3.0.0 已解决，下面的老结论作废）

**老结论**（v3.0.0 之前）：一卡通余额由易校园/小付宝系统管理（`compus.xiaofubao.com`），
该 API 有签名保护（`sign` Header），签名算法在网易易盾加固的 native 层中，无法通过常规方式获取。

**真实情况**：**不用去碰易校园，趣智校园自己代理了这个接口。**

```
GET /settlement/campus/userInfo   →   {"amount":"16.76","signStatus":1,"studentNumber":"...","studentName":"..."}
```

本 App 从 v3.0.0 起直接读它显示真实余额，未签约时回退本地估算。

> 当初之所以判断「做不了」，是**只盯着易校园的域名**，没注意到趣智校园这边有现成代理。
> 教训：先在自己已经能认证的系统里找一遍，再去想怎么破第三方的签名。

**唯一的真限制**：学生**没签约免密支付**（`signStatus == 0`）时服务端不给 `amount`，
这种账号仍然只能本地估算。

详见 [4.9](#49-账号信息与一卡通)。

### 9.8 GET 请求也要补 `telPhone`

9.2 说的是 POST 请求体里 `telephone` 和 `telPhone` 要一起传。
**GET 请求同样有这个要求**——如果用拦截器统一往 URL 上补认证参数，
很容易只补了 `telephone`（因为绝大多数接口只认它），然后在
`/settlement/campus/userInfo` 上吃瘪：

```json
{"success": false, "errorCode": 1, "errorMessage": "手机号不能为空", "data": null}
```

**HTTP 还是 200。** 少了 `telPhone` 就报「手机号不能为空」，而状态码一切正常，
只能靠读 `errorMessage` 发现。两个都补上最省事。

### 9.9 别假设「HTTP 200 = 成功」

趣智校园这边，**接口失败也经常返回 HTTP 200**，错误全在 body 的 `success` / `errorCode` 里。
上面 9.8 那个「手机号不能为空」就是典型：状态码 200、`data` 是 `null`，
如果代码是 `resp.data ?: return` 这种写法，就会**静默降级**成「功能没生效」，
日志里还看不出来。判断成功一律看 `success` 字段。

---

## 附录：Retrofit 接口定义参考

```kotlin
interface QzxyService {

    @FormUrlEncoded
    @POST("/user/login")
    fun login(
        @Field("telephone") telephone: String,
        @Field("password") password: String,
        @Field("phoneSystem") phoneSystem: String = "android",
        @Field("type") type: Int = 0,
        @Field("version") version: String = "6.5.24"
    ): Call<ResponseBody>

    @GET("/account/wallet")
    fun getWallet(): Call<ResponseBody>

    @GET("/device/info/mac")
    fun getDeviceInfo(@Query("macAddress") mac: String): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/downRate/rateOrder")
    fun downRate(
        @Field("xfModel") xfModel: Int = 0,
        @Field("snCode") snCode: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/order/tcpDevice/query/downRateResult")
    fun downRateResult(
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

    /** 「换一个」：只返回候选码，不生效。每天 20 次 */
    @FormUrlEncoded
    @POST("/account/useCode/new/generate")
    fun generateUseCode(@FieldMap auth: Map<String, String>): Call<ResponseBody>

    /** 「确定领取」：唯一会让候选码生效的一步 */
    @FormUrlEncoded
    @POST("/account/useCode/new/set")
    fun setUseCode(
        @Field("useCode") useCode: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    // ── 账号信息与一卡通（v3.0.0） ──

    /** 姓名 / 学号 / 校园卡绑定状态。GET 会自动带上认证参数 */
    @GET("/account/info")
    fun getAccountInfo(): Call<ResponseBody>

    /**
     * 一卡通真实余额。
     *
     * ⚠️ 它要的是 `telPhone`（不是 `telephone`），少传会返回
     * `errorCode=1 手机号不能为空`——而且 **HTTP 仍是 200**。
     * ⚠️ 未签约（signStatus=0）时不给 amount。
     */
    @GET("/settlement/campus/userInfo")
    fun getCampusUserInfo(): Call<ResponseBody>

    /** 项目（学校）信息，projectName 就是学校名 */
    @GET("/project/info/triple")
    fun getProjectInfo(): Call<ResponseBody>

    // ── 手机号与密码（v3.0.0） ──

    /** 更换手机号。code 是发到**新**号（typeId=5）的验证码 */
    @FormUrlEncoded
    @POST("/user/phone/update")
    fun updatePhone(
        @Field("newTelephone") newTelephone: String,
        @Field("code") code: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /** 修改密码。两个密码都是 MD5 取后 10 位大写 */
    @FormUrlEncoded
    @POST("/user/password/update")
    fun updatePassword(
        @Field("oldPassword") oldPassword: String,
        @Field("password") password: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    /** 重置密码：**不需要旧密码**，只要 typeId=2 发来的验证码 */
    @FormUrlEncoded
    @POST("/user/password/forget")
    fun forgetPassword(
        @Field("password") password: String,
        @Field("code") code: String,
        @FieldMap auth: Map<String, String>
    ): Call<ResponseBody>

    // ── 短信验证码（v2.1.0：secret 由手机号推导，任何手机号可用） ──
    // secret = MD5(手机号前3位 + 手机号后4位 + "klcx")，见 utils/SignUtils.kt
    @GET("/user/verification/code/get")
    fun getVerificationCode(
        @Query("telephone") telephone: String,
        @Query("typeId") typeId: Int = 3,
        @Query("platform") platform: Int = 1,
        @Query("secret") secret: String
    ): Call<ResponseBody>

    @FormUrlEncoded
    @POST("/user/registerAndLogin")
    fun registerAndLogin(
        @Field("telephone") telephone: String,
        @Field("smsCode") smsCode: String,
        @Field("type") type: Int = 5,
        @Field("phoneSystem") phoneSystem: String = "android",
        @Field("version") version: String = "6.5.24"
    ): Call<ResponseBody>

    companion object {
        const val BASE_URL = "https://v3-api.china-qzxy.cn"
    }
}
```
