package com.jev.relationship.xposed

import com.jev.relationship.xposed.hook.WechatVersion

object WechatUiHookActivation {
    fun shouldInstall(
        packageName: String?,
        processName: String?,
        version: WechatVersion,
        pairingToken: String?,
    ): Boolean = WechatHookActivation.shouldInstall(
        packageName = packageName,
        processName = processName,
        version = version,
        pairingToken = pairingToken,
    )
}
