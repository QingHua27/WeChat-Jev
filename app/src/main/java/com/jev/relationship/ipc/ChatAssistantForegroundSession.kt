package com.jev.relationship.ipc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import com.jev.relationship.MainActivity

/** Keep user-requested analysis runnable while WeChat, rather than Jev, is visible. */
class ChatAssistantForegroundSession(private val service: Service) {
    private val requests = mutableSetOf<String>()

    fun acquire(requestId: String) {
        if (requests.isEmpty()) {
            service.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AI 分析进度", NotificationManager.IMPORTANCE_LOW),
            )
            val notification = Notification.Builder(service, CHANNEL_ID)
                .setSmallIcon(com.jev.relationship.R.drawable.ic_notification)
                .setContentTitle("Jev 正在分析")
                .setContentText("正在读取聊天记录并等待模型结果，完成后自动结束")
                .setContentIntent(PendingIntent.getActivity(
                    service, 0, Intent(service, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                service.startForeground(NOTIFICATION_ID, notification)
            }
        }
        requests += requestId
    }

    fun release(requestId: String) {
        if (requests.remove(requestId) && requests.isEmpty()) {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        }
    }

    fun clear() {
        if (requests.isEmpty()) return
        requests.clear()
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    private companion object {
        const val CHANNEL_ID = "jev_chat_analysis"
        const val NOTIFICATION_ID = 702
    }
}
