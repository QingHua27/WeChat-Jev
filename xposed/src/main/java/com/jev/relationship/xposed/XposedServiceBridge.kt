package com.jev.relationship.xposed

import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** Owns the process-wide LibXposed listener so short-lived Activities are never retained. */
object XposedServiceBridge : XposedServiceHelper.OnServiceListener {
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun whenAvailable(listener: () -> Unit) {
        listeners.add(listener)
        if (service != null) listener()
    }
    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize() {
        if (initialized) return
        XposedServiceHelper.registerListener(this)
        initialized = true
    }

    fun remotePreferences(): SharedPreferences? = runCatching {
        service?.getRemotePreferences(XposedModulePreferences.NAME)
    }.getOrNull()

    override fun onServiceBind(service: XposedService) {
        this.service = service
        listeners.forEach { it() }
    }

    override fun onServiceDied(service: XposedService) {
        if (this.service === service) this.service = null
    }
}
