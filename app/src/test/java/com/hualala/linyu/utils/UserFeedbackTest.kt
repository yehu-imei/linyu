package com.hualala.linyu.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class UserFeedbackTest {
    @Test fun chineseBusinessReasonIsPreserved() {
        assertEquals("余额不足，请充值", UserFeedback.message("余额不足，请充值"))
        assertEquals("设备不在线", UserFeedback.message("  设备不在线  "))
        assertEquals("每天只能领取一次使用码", UserFeedback.message("1天只能领取一次使用码"))
    }

    @Test fun technicalErrorsAreNotDisplayed() {
        assertEquals("请求失败，请稍后重试", UserFeedback.message("Internal Server Error"))
        assertEquals("请求失败，请稍后重试", UserFeedback.message("请求失败：NSURLErrorDomain(-1009)"))
        assertEquals("请求失败，请稍后重试", UserFeedback.message("请求失败\n内部堆栈"))
        assertEquals("关闭结果未确认", UserFeedback.message(null, "关闭结果未确认"))
    }

    @Test fun networkErrorsUseShortChineseMessages() {
        assertEquals("连接超时，请重试", UserFeedback.message("请求失败：SocketTimeoutException"))
        assertEquals("网络连接失败，请检查网络", UserFeedback.message("Unable to resolve host example.invalid"))
    }
}
