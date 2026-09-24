package com.jev.relationship.xposed.ipc

internal class IpcClientStartupGate(
    private val startConnection: () -> Unit,
) {
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        startConnection()
    }
}
