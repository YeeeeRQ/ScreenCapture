package com.example.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.capture.service.ScreenRecordService

class PermissionActivity : Activity() {
    
    companion object {
        private const val TAG = "PermissionActivity"
    }
    
    private val handler = Handler(Looper.getMainLooper())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "PermissionActivity created")
        
        // 请求 MediaProjection 权限
        requestMediaProjectionPermission()
    }
    
    private fun requestMediaProjectionPermission() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, 1001)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == 1001) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d(TAG, "Permission granted")
                
                // 启动录制服务
                val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_START
                    putExtra("result_code", resultCode)
                    putExtra("data", data)
                }
                startForegroundService(serviceIntent)
            } else {
                Log.d(TAG, "Permission denied")
            }
            
            // 延迟一点关闭，让服务有时间启动
            handler.postDelayed({
                finish()
            }, 500)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "PermissionActivity destroyed")
    }
}
