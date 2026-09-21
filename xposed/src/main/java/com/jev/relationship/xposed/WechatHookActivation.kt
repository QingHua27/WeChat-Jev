package com.jev.relationship.xposed

import com.jev.relationship.xposed.hook.WechatHookGate
import com.jev.relationship.xposed.hook.WechatVersion

object WechatHookActivation {
    fun shouldInstall(
        packageName: String?,
        processName: String?,
        version: WechatVersion,
        pairingToken: String?,
    ): Boolean = !pairingToken.isNullOrBlank() &&
        WechatHookGate.accepts(packageName, processName, version)
}
