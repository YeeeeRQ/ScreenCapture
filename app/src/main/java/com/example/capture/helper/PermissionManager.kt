package com.example.capture.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionManager {

    enum class PermissionStep {
        NONE,
        OVERLAY,
        NOTIFICATION,
        STORAGE,
        COMPLETE
    }

    data class PermissionStatus(
        val hasOverlay: Boolean,
        val hasNotification: Boolean,
        val hasStorage: Boolean
    ) {
        val allGranted: Boolean
            get() = hasOverlay && hasNotification && hasStorage

        val nextStep: PermissionStep
            get() = when {
                !hasOverlay -> PermissionStep.OVERLAY
                !hasNotification -> PermissionStep.NOTIFICATION
                !hasStorage -> PermissionStep.STORAGE
                else -> PermissionStep.COMPLETE
            }

        val progress: Int
            get() = listOf(hasOverlay, hasNotification, hasStorage).count { it }

        val totalSteps: Int = 3
    }

    fun getPermissionStatus(context: Context): PermissionStatus {
        return PermissionStatus(
            hasOverlay = hasOverlayPermission(context),
            hasNotification = hasNotificationPermission(context),
            hasStorage = hasManageStoragePermission(context)
        )
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

    fun requestNextPermission(activity: Activity, currentStep: PermissionStep) {
        when (currentStep) {
            PermissionStep.OVERLAY -> requestOverlayPermission(activity)
            PermissionStep.NOTIFICATION -> requestNotificationPermission(activity)
            PermissionStep.STORAGE -> requestManageStoragePermission(activity)
            else -> {}
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

    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
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
}