package com.example.capture.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionHelper {
    private const val REQUEST_PERMISSIONS = 1001
    private const val REQUEST_MEDIA_PROJECTION = 1002
    private const val REQUEST_OVERLAY_PERMISSION = 1003

    fun hasAllPermissions(context: Context): Boolean {
        return hasOverlayPermission(context) &&
               hasNotificationPermission(context) &&
               hasManageStoragePermission(context)
    }

    fun hasMediaProjectionPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val result = context.getSharedPreferences("media_projection", Context.MODE_PRIVATE)
            result.getInt("result_code", -1) != -1
        } else {
            true
        }
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasManageStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun requestPermissions(activity: Activity) {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    activity,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                permissions.toTypedArray(),
                REQUEST_PERMISSIONS
            )
        }
    }

    fun requestOverlayPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        }
    }

    fun requestManageStoragePermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = android.net.Uri.parse("package:${activity.packageName}")
                activity.startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                activity.startActivity(intent)
            }
        }
    }

    fun requestMediaProjectionPermission(activity: Activity, resultCode: Int, data: Intent) {
        activity.getSharedPreferences("media_projection", Context.MODE_PRIVATE)
            .edit()
            .putInt("result_code", resultCode)
            .putString("result_data", data.toUri(Intent.URI_INTENT_SCHEME))
            .apply()
    }

    fun getMediaProjectionData(context: Context): Pair<Int, Intent>? {
        val prefs = context.getSharedPreferences("media_projection", Context.MODE_PRIVATE)
        val resultCode = prefs.getInt("result_code", -1)
        val dataString = prefs.getString("result_data", null)
        
        return if (resultCode != -1 && dataString != null) {
            try {
                val data = Intent(dataString)
                Pair(resultCode, data)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    fun clearMediaProjectionPermission(context: Context) {
        context.getSharedPreferences("media_projection", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
