package com.example.capture.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

class RecordingNotificationTracker(
    private val context: Context,
    private val notificationHelper: NotificationHelper
) {
    companion object {
        private const val TAG = RecordingConstants.TAG
        private const val NOTIFICATION_ID = RecordingConstants.NOTIFICATION_ID
    }

    private val handler = Handler(Looper.getMainLooper())
    private var notificationUpdateRunnable: Runnable? = null
    private var recordingStartTime: Long = 0

    var onUpdateState: ((String) -> Unit)? = null

    fun startTracking(startTime: Long) {
        recordingStartTime = startTime
        startNotificationUpdate()
    }

    fun stopTracking() {
        notificationUpdateRunnable?.let { handler.removeCallbacks(it) }
        notificationUpdateRunnable = null
    }

    private fun startNotificationUpdate() {
        notificationUpdateRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - recordingStartTime
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / 1000) / 60
                val timeText = String.format("%02d:%02d", minutes, seconds)

                onUpdateState?.invoke(timeText)

                val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
                val notification = notificationHelper.createRecordingNotification(timeText)
                notificationManager.notify(NOTIFICATION_ID, notification)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(notificationUpdateRunnable!!)
    }
}