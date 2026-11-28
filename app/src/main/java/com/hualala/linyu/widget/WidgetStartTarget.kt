package com.hualala.linyu.widget

import android.content.Intent
import com.hualala.linyu.api.NetworkModule
import com.hualala.linyu.data.RoomBindingPlugin
import com.hualala.linyu.data.ShowerController
import com.hualala.linyu.utils.PrefsHelper

/** Account and device shown when the launcher received the start button. */
internal data class WidgetStartTarget(val snCode: String, val owner: String) {
    fun matches(currentSnCode: String, currentOwner: String): Boolean =
        snCode.isNotBlank() && owner.isNotBlank() && snCode == currentSnCode && owner == currentOwner

    fun matchesSelection(): Boolean {
        val session = NetworkModule.session()
        return PrefsHelper.isLoggedIn && session.loginCode == PrefsHelper.loginCode &&
            matches(PrefsHelper.lastDeviceSnCode, session.owner) &&
            RoomBindingPlugin.allows(ShowerController.roomFilterName())
    }

    fun canStart(): Boolean = matchesSelection() && !ShowerController.isRunning(snCode)

    fun putInto(intent: Intent) {
        intent.putExtra(EXTRA_SN, snCode)
        intent.putExtra(EXTRA_OWNER, owner)
    }

    companion object {
        private const val EXTRA_SN = "widgetStartSn"
        private const val EXTRA_OWNER = "widgetStartOwner"

        fun fromIntent(intent: Intent): WidgetStartTarget? {
            val snCode = intent.getStringExtra(EXTRA_SN)?.takeIf(String::isNotBlank) ?: return null
            val owner = intent.getStringExtra(EXTRA_OWNER)?.takeIf(String::isNotBlank) ?: return null
            return WidgetStartTarget(snCode, owner)
        }
    }
}

internal class WidgetOpenRecovery(val target: WidgetStartTarget, val attemptedAt: Long)
