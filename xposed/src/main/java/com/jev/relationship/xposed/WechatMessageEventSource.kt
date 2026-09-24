package com.jev.relationship.xposed

import com.jev.relationship.ipc.CapturedMessage

interface WechatMessageEventSource {
    fun start(emit: (CapturedMessage) -> Unit)

    fun stop()
}

class Phase3SimulatedMessageSource : WechatMessageEventSource {
    private var emit: ((CapturedMessage) -> Unit)? = null

    override fun start(emit: (CapturedMessage) -> Unit) {
        this.emit = emit
    }

    override fun stop() {
        emit = null
    }

    fun emitForTest(message: CapturedMessage) {
        emit?.invoke(message)
    }
}
