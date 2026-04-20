package com.example.capture.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.capture.PermissionActivity
import com.example.capture.R

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = RecordingConstants.CHANNEL_ID
        const val NOTIFICATION_ID = RecordingConstants.NOTIFICATION_ID
        const val ACTION_STOP = RecordingConstants.ACTION_STOP
    }

    fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    fun createNotification(): Notification {
        val stopIntent = Intent(context, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(context, PermissionActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.recording_title))
            .setContentText(context.getString(R.string.recording_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.stop_recording),
                stopPendingIntent
            )
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    fun createRecordingNotification(recordingTime: String): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.recording_title))
            .setContentText("${context.getString(R.string.recording_in_progress)} $recordingTime")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}