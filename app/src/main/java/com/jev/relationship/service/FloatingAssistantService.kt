package com.jev.relationship.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.jev.relationship.MainActivity
import com.jev.relationship.domain.realtime.RealtimeAnalysisCoordinator
import com.jev.relationship.domain.surface.AssistantSurfaceCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps the realtime coordinator alive. Analysis cards are embedded by the Xposed module
 * in WeChat's chat hierarchy; this service intentionally creates no WindowManager window.
 */
@AndroidEntryPoint
class FloatingAssistantService : Service() {
    @Inject
    lateinit var surfaceCoordinator: AssistantSurfaceCoordinator

    @Inject
    lateinit var realtimeAnalysisCoordinator: RealtimeAnalysisCoordinator

    private lateinit var lifecycleCoordinator: FloatingAssistantLifecycle

    override fun onCreate() {
        super.onCreate()
        lifecycleCoordinator = FloatingAssistantLifecycle(
            surfaceCoordinator = surfaceCoordinator,
            onRealtimeStart = realtimeAnalysisCoordinator::start,
            onRealtimeStop = realtimeAnalysisCoordinator::stop,
        )
        startForegroundServiceNotification()
        lifecycleCoordinator.onAssistantReady()
    }

    override fun onDestroy() {
        if (::lifecycleCoordinator.isInitialized) {
            lifecycleCoordinator.onDestroy()
        }
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
                "Jev 聊天内嵌助手",
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
            .setSmallIcon(com.jev.relationship.R.drawable.ic_notification)
            .setContentTitle("Jev 聊天内嵌助手已开启")
            .setContentText("分析结果显示在支持的微信聊天窗口内，不会自动发送消息")
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
