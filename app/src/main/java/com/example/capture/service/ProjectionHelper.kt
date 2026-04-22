package com.example.capture.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.capture.PermissionActivity
import java.lang.ref.WeakReference

class ProjectionHelper(private val context: Context) {

    companion object {
        private const val TAG = RecordingConstants.TAG
    }

    private var savedResultCode: Int = 0
    private var savedData: Intent? = null
    private var activityRef: WeakReference<Activity>? = null
    private var pendingStartRecording = false

    private val prefs by lazy {
        context.getSharedPreferences("screen_record_prefs", Context.MODE_PRIVATE)
    }

    fun setActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun clearActivity() {
        activityRef = null
    }

    fun saveProjectionData(resultCode: Int, data: Intent) {
        savedResultCode = resultCode
        savedData = data
        Log.d(TAG, "Projection data saved to memory: resultCode=$resultCode")
    }

    fun getSavedData(): Pair<Int, Intent?>? {
        return if (savedResultCode != 0 && savedData != null) {
            Pair(savedResultCode, savedData)
        } else {
            null
        }
    }

    fun hasSavedProjectionData(): Boolean {
        return savedResultCode != 0 && savedData != null
    }

    fun setPendingStartRecording(pending: Boolean) {
        pendingStartRecording = pending
    }

    fun isPendingStartRecording(): Boolean = pendingStartRecording

    fun loadProjectionDataFromPrefs(): Boolean {
        val resultCode = prefs.getInt("result_code", 0)
        val dataString = prefs.getString("data", null)

        if (resultCode != 0 && dataString != null) {
            try {
                savedData = Intent.parseUri(dataString, 0)
                savedResultCode = resultCode
                Log.d(TAG, "Projection data loaded from SharedPreferences: resultCode=$resultCode")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error loading projection data: ${e.message}")
            }
        }
        return false
    }

    fun requestMediaProjectionPermission() {
        Log.d(TAG, "Opening PermissionActivity for permission request")
        openMainActivityForPermission()
    }

    private fun openMainActivityForPermission() {
        try {
            val ctx = activityRef?.get() ?: context.applicationContext

            val intent = Intent(ctx, PermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            ctx.startActivity(intent)
            Log.d(TAG, "Opened PermissionActivity for permission request")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open PermissionActivity: ${e.message}")
            showNoPermissionNotification()
        }
    }

    fun showNoPermissionNotification() {
        try {
            val channel = NotificationChannel(
                "floating_channel",
                "悬浮窗提示",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "提醒用户获取录制权限"
            }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)

            val openIntent = Intent(context, PermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, "floating_channel")
                .setContentTitle("需要获取权限")
                .setContentText("点击开始屏幕录制")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(2, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification: ${e.message}")
        }
    }
}