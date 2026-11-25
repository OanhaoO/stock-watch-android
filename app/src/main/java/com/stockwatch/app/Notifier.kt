package com.stockwatch.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.stockwatch.app.core.NotificationContent

object NotifierChannels {
    const val STATUS = "watch_status"
    const val T_RANGE = "t_range"
    const val BIAS = "bias_cross_proxy"
    const val CHANGE = "daily_change"
    const val SYSTEM = "system_error"
}

class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(NotifierChannels.STATUS, "盯盘状态", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "前台服务常驻通知"
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(NotifierChannels.T_RANGE, "做T提醒", NotificationManager.IMPORTANCE_HIGH),
            )
            manager.createNotificationChannel(
                NotificationChannel(NotifierChannels.BIAS, "乖离、交叉与代理", NotificationManager.IMPORTANCE_DEFAULT),
            )
            manager.createNotificationChannel(
                NotificationChannel(NotifierChannels.CHANGE, "当日涨跌幅", NotificationManager.IMPORTANCE_DEFAULT),
            )
            manager.createNotificationChannel(
                NotificationChannel(NotifierChannels.SYSTEM, "运行异常", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    fun notificationsAllowed(channel: String = NotifierChannels.BIAS): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val settings = manager.getNotificationChannel(channel)
            if (settings != null && settings.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    fun post(
        content: NotificationContent,
        channel: String,
        group: String,
        dedupKey: String,
    ): Boolean {
        if (!notificationsAllowed(channel)) return false
        val id = notificationId("$group|$channel|$dedupKey")
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.body.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(content.body))
            .setContentIntent(contentIntent(id))
            .setAutoCancel(true)
            .setGroup(group)
            .setCategory(Notification.CATEGORY_REMINDER)
        if (channel == NotifierChannels.T_RANGE || channel == NotifierChannels.SYSTEM) {
            builder.setPriority(Notification.PRIORITY_HIGH)
        }
        manager.notify(id, builder.build())
        return true
    }

    fun statusNotification(text: String): Notification = Notification.Builder(context, NotifierChannels.STATUS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("盯盘助手")
        .setContentText(text)
        .setContentIntent(contentIntent(STATUS_ID))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_STATUS)
        .build()

    fun updateStatus(text: String) {
        manager.notify(STATUS_ID, statusNotification(text))
    }

    private fun contentIntent(id: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, id, intent, flags)
    }

    private fun notificationId(key: String): Int {
        val value = key.hashCode() and Int.MAX_VALUE
        return if (value == 0 || value == STATUS_ID) value + 1 else value
    }

    companion object {
        const val STATUS_ID = 10_001
    }
}
