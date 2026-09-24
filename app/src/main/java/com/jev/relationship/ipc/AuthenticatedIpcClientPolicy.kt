package com.jev.relationship.ipc

import android.os.IBinder

object AuthenticatedIpcClientPolicy {
    fun allows(
        authenticatedBinder: IBinder?,
        replyBinder: IBinder?,
        callerPackages: Set<String>,
    ): Boolean = authenticatedBinder != null &&
        replyBinder === authenticatedBinder &&
        IpcProtocol.WECHAT_PACKAGE in callerPackages
}
