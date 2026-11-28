package com.hualala.linyu.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStartTargetTest {
    private val rendered = WidgetStartTarget("device-A", "user:school:account")

    @Test
    fun matchesOnlyTheAccountAndDeviceShownOnTheWidget() {
        assertTrue(rendered.matches("device-A", "user:school:account"))
        assertFalse(rendered.matches("device-B", "user:school:account"))
        assertFalse(rendered.matches("device-A", "user:school:other-account"))
        assertFalse(rendered.matches("device-A", "other-user:school:account"))
    }

    @Test
    fun rejectsAnUnboundButton() {
        assertFalse(WidgetStartTarget("", rendered.owner).matches("", rendered.owner))
        assertFalse(WidgetStartTarget(rendered.snCode, "").matches(rendered.snCode, ""))
    }

    @Test
    fun unknownRecoverySurvivesGenericRedrawAndBelongsToTheDisplayedAccountAndDevice() {
        WidgetBridge.markUnknown(rendered, 100)
        val recovery = requireNotNull(WidgetBridge.recoveryFor(rendered))
        try {
            WidgetBridge.clearBusy()
            assertSame(recovery, WidgetBridge.recoveryFor(rendered))
            assertNull(WidgetBridge.recoveryFor(rendered.copy(snCode = "device-B")))
            assertNull(WidgetBridge.recoveryFor(rendered.copy(owner = "another:school:account")))
        } finally {
            WidgetBridge.clearRecovery(recovery)
        }
        assertNull(WidgetBridge.recoveryFor(rendered))
    }

    @Test
    fun oldCompletionCannotClearTheNewUnknownAttempt() {
        WidgetBridge.markUnknown(rendered, 100)
        val previous = requireNotNull(WidgetBridge.recoveryFor(rendered))
        WidgetBridge.markUnknown(rendered, 200)
        val latest = WidgetBridge.recoveryFor(rendered)
        try {
            assertNotNull(latest)
            WidgetBridge.clearRecovery(previous)
            assertSame(latest, WidgetBridge.recoveryFor(rendered))
        } finally {
            latest?.let(WidgetBridge::clearRecovery)
        }
    }
}
