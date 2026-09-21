package com.jev.relationship.service

import android.app.Notification
import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.jev.relationship.MainActivity
import com.jev.relationship.domain.realtime.RealtimeAnalysisCoordinator
import com.jev.relationship.domain.surface.AssistantSurfaceCoordinator
import com.jev.relationship.ui.theme.JevTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class FloatingAssistantService : Service() {
    @Inject
    lateinit var surfaceCoordinator: AssistantSurfaceCoordinator

    @Inject
    lateinit var realtimeAnalysisCoordinator: RealtimeAnalysisCoordinator

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var overlayOwner: OverlayViewTreeOwner? = null
    private lateinit var lifecycleCoordinator: FloatingAssistantLifecycle

    override fun onCreate() {
        super.onCreate()
        lifecycleCoordinator = FloatingAssistantLifecycle(
            surfaceCoordinator = surfaceCoordinator,
            onRealtimeStart = realtimeAnalysisCoordinator::start,
            onRealtimeStop = realtimeAnalysisCoordinator::stop,
        )
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        startForegroundServiceNotification()
        lifecycleCoordinator.onOverlayReady()
        val owner = OverlayViewTreeOwner()
        overlayOwner = owner
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                JevTheme {
                    FloatingAssistantPill(
                        state = surfaceCoordinator.state.collectAsState().value,
                        onClose = { stopSelf() },
                    )
                }
            }
        }
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = 96
        }
        windowManager = getSystemService(WindowManager::class.java)
        runCatching { windowManager?.addView(view, layoutParams) }
            .onSuccess { overlayView = view }
            .onFailure {
                view.disposeComposition()
                stopSelf()
            }
    }

    override fun onDestroy() {
        if (::lifecycleCoordinator.isInitialized) {
            lifecycleCoordinator.onDestroy()
        }
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        windowManager = null
        overlayOwner?.destroy()
        overlayOwner = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundServiceNotification() {
        val channelId = "jev_assistant"
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "Jev 悬浮助手",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Jev 悬浮助手已开启")
            .setContentText("仅显示只读分析入口，不会自动发送消息")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 701
    }
}
