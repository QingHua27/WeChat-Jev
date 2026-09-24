package com.jev.relationship

import android.app.Application
import com.jev.relationship.xposed.XposedServiceBridge
import dagger.hilt.android.HiltAndroidApp
import com.jev.relationship.data.settings.AutomaticXposedConnection
import com.jev.relationship.data.settings.XposedIntegrationRepository
import javax.inject.Inject
import kotlinx.coroutines.*

@HiltAndroidApp
class JevApplication : Application() {
    @Inject lateinit var integrationRepository: XposedIntegrationRepository
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val connection = AutomaticXposedConnection(integrationRepository, XposedServiceBridge::remotePreferences)
        XposedServiceBridge.whenAvailable {
            connectionScope.launch {
                repeat(4) { attempt ->
                    val ready = try {
                        connection.connect()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        android.util.Log.w("JevAutoConnect", "setup failed type=${error.javaClass.simpleName}")
                        false
                    }
                    if (ready) {
                        android.util.Log.i("JevAutoConnect", "module credentials ready")
                        return@launch
                    }
                    delay(500L shl attempt)
                }
            }
        }
        XposedServiceBridge.initialize()
    }
}
