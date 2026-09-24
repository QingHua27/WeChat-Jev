package com.jev.relationship.xposed

import com.jev.relationship.ipc.IpcProtocol

object WechatScope {
    fun isSupported(packageName: String?): Boolean = packageName == IpcProtocol.WECHAT_PACKAGE
}
