package com.example.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.capture.service.ScreenRecordService

class PermissionActivity : Activity() {

    companion object {
        private const val TAG = "PermissionActivity"

        var pendingResultCode: Int = -1
        var pendingData: Intent? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
        )
        window.setFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
        )

        setupUI()

        Log.d(TAG, "PermissionActivity created")

        requestMediaProjectionPermission()
    }

    private fun setupUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F5F5.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val message = TextView(this).apply {
            text = "正在请求屏幕录制权限..."
            textSize = 18f
            setTextColor(0xFF333333.toInt())
            gravity = android.view.Gravity.CENTER
        }

        val progress = ProgressBar(this).apply {
            isIndeterminate = true
        }

        layout.addView(progress)
        layout.addView(message)

        setContentView(layout)
    }

    private fun requestMediaProjectionPermission() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 1001) {
            val granted = resultCode == Activity.RESULT_OK && data != null

            val resultIntent = Intent(ScreenRecordService.BROADCAST_AUTHORIZATION_RESULT).apply {
                putExtra("granted", granted)
                putExtra("result_code", resultCode)
                if (!granted) {
                    putExtra("error", "User denied or cancelled")
                }
            }
            sendBroadcast(resultIntent)

            if (granted) {
                Log.d(TAG, "Permission granted, resultCode=$resultCode")

                pendingResultCode = resultCode
                pendingData = data

                val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_START
                }
                startForegroundService(serviceIntent)
            } else {
                Log.d(TAG, "Permission denied")
            }

            handler.postDelayed({
                finishAffinity()
            }, 300)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "PermissionActivity destroyed")
    }
}