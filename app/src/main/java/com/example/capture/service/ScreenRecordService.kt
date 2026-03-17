package com.example.capture.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import com.example.capture.MainActivity
import com.example.capture.PermissionActivity
import com.example.capture.R
import com.example.capture.view.FloatingView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference

class ScreenRecordService : Service() {
    
    companion object {
        private const val TAG = "ScreenRecord"
        const val CHANNEL_ID = "screen_record_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.example.capture.START"
        const val ACTION_STOP = "com.example.capture.STOP"
        const val ACTION_TOGGLE = "com.example.capture.TOGGLE"
        
        const val VIDEO_WIDTH = 720
        const val VIDEO_HEIGHT = 1280
        const val VIDEO_BIT_RATE = 4000000
        const val VIDEO_FRAME_RATE = 30
        
        const val MODE_REAUTH = "reauth"
        const val MODE_REUSE = "reuse"
    }
    
    private var recordingMode = MODE_REAUTH

    interface RecordingCallback {
        fun onRecordingStarted()
        fun onRecordingStopped()
        fun onRecordingError(error: String)
    }

    private var recordingCallback: RecordingCallback? = null
    
    private var savedResultCode: Int = 0
    private var savedData: Intent? = null
    
    private val prefs by lazy {
        getSharedPreferences("screen_record_prefs", Context.MODE_PRIVATE)
    }
    
    private val binder = LocalBinder()
    private var floatingView: FloatingView? = null
    
    fun setFloatingView(view: FloatingView?) {
        floatingView = view
    }
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var outputFile: File? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var recordingStartTime: Long = 0
    private var notificationUpdateRunnable: Runnable? = null
    
    private var activityRef: WeakReference<Activity>? = null
    private var pendingStartRecording = false
    
    fun setActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }
    
    fun clearActivity() {
        activityRef = null
    }
    
    private fun requestMediaProjectionPermission() {
        Log.d(TAG, "Opening PermissionActivity for permission request")
        openMainActivityForPermission()
    }
    
    private fun openMainActivityForPermission() {
        try {
            val context = activityRef?.get() ?: applicationContext
            
            val intent = Intent(context, PermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
            Log.d(TAG, "Opened PermissionActivity for permission request")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open PermissionActivity: ${e.message}")
            showNoPermissionNotification()
        }
    }

    fun setCallback(callback: RecordingCallback?) {
        recordingCallback = callback
    }

    fun saveProjectionData(resultCode: Int, data: Intent) {
        savedResultCode = resultCode
        savedData = data
        Log.d(TAG, "Projection data saved to memory: resultCode=$resultCode")
        
        if (pendingStartRecording && resultCode == Activity.RESULT_OK) {
            pendingStartRecording = false
            Log.d(TAG, "Auto-starting recording after permission granted")
            startRecordingInternal(resultCode, data)
        }
    }
    
    fun setRecordingMode(mode: String) {
        recordingMode = mode
        Log.d(TAG, "Recording mode set to: $mode")
    }

    private fun loadProjectionDataFromPrefs(): Boolean {
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

    fun hasSavedProjectionData(): Boolean {
        return savedResultCode != 0 && savedData != null
    }

    fun toggleRecording() {
        Log.d(TAG, "toggleRecording called, isRecording=$isRecording")
        
        if (isRecording) {
            stopRecording()
        } else {
            if (savedResultCode == Activity.RESULT_OK && savedData != null) {
                Log.d(TAG, "Starting recording with saved data")
                startRecordingInternal(savedResultCode, savedData!!)
            } else {
                Log.d(TAG, "No valid projection data, requesting permission directly")
                requestMediaProjectionPermission()
            }
        }
    }
    
    private fun showNoPermissionNotification() {
        try {
            val channel = NotificationChannel(
                "floating_channel",
                "悬浮窗提示",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "提醒用户获取录制权限"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            
            val openIntent = Intent(this, PermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val notification = NotificationCompat.Builder(this, "floating_channel")
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

    private val projectionCallback: MediaProjection.Callback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopRecording()
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): ScreenRecordService = this@ScreenRecordService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called with action: ${intent?.action}")
        
        if (intent?.action == ACTION_START) {
            val resultCode = PermissionActivity.pendingResultCode
            val data = PermissionActivity.pendingData
            
            Log.d(TAG, "Reading from static: resultCode=$resultCode, data=${data != null}")
            
            if (resultCode == Activity.RESULT_OK && data != null) {
                saveProjectionData(resultCode, data)
                startRecordingInternal(resultCode, data)
                
                // 显示 FloatingView
                showFloatingView()
                
                // 清除静态变量
                PermissionActivity.pendingResultCode = -1
                PermissionActivity.pendingData = null
            }
        }
        
        return START_STICKY
    }
    
    private fun showFloatingView() {
        try {
            if (floatingView == null) {
                floatingView = FloatingView(this)
            }
            floatingView?.setService(this)
            floatingView?.isRecording = true
            floatingView?.show()
            Log.d(TAG, "FloatingView shown from Service")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing FloatingView: ${e.message}")
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, PermissionActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_title))
            .setContentText(getString(R.string.recording_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.stop_recording),
                stopPendingIntent
            )
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
    
    private fun startRecordingInternal(resultCode: Int, data: Intent) {
        if (isRecording) return

        Log.d(TAG, "=== startRecording called (direct) ===")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground: ${e.message}")
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        mediaProjection?.registerCallback(projectionCallback, handler)

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        val density = metrics.densityDpi
        val width = VIDEO_WIDTH.coerceAtMost(metrics.widthPixels)
        val height = VIDEO_HEIGHT.coerceAtMost(metrics.heightPixels)

        outputFile = createOutputFile()
        
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(VIDEO_BIT_RATE)
            setVideoFrameRate(VIDEO_FRAME_RATE)
            setVideoSize(width, height)
            setOutputFile(outputFile?.absolutePath)
            
            prepare()
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder?.surface,
            null,
            handler
        )

        try {
            mediaRecorder?.start()
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            
            startNotificationUpdate()
            recordingCallback?.onRecordingStarted()
        } catch (e: Exception) {
            Log.e(TAG, "ERROR starting recording: ${e.message}")
            recordingCallback?.onRecordingError(e.message ?: "Unknown error")
            cleanup()
        }
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        if (isRecording) return

        Log.d(TAG, "=== startRecording called ===")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground: ${e.message}")
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        mediaProjection?.registerCallback(projectionCallback, handler)

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        val density = metrics.densityDpi
        val width = VIDEO_WIDTH.coerceAtMost(metrics.widthPixels)
        val height = VIDEO_HEIGHT.coerceAtMost(metrics.heightPixels)

        Log.d(TAG, "Screen: ${metrics.widthPixels}x${metrics.heightPixels}, recording: ${width}x${height}, density: $density")

        outputFile = createOutputFile()
        
        Log.d(TAG, "Creating MediaRecorder...")
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(VIDEO_BIT_RATE)
            setVideoFrameRate(VIDEO_FRAME_RATE)
            setVideoSize(width, height)
            setOutputFile(outputFile?.absolutePath)
            
            Log.d(TAG, "Calling prepare()...")
            prepare()
            Log.d(TAG, "prepare() success!")
        }

        Log.d(TAG, "Creating VirtualDisplay...")
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder?.surface,
            null,
            handler
        )

        try {
            Log.d(TAG, "Calling mediaRecorder.start()...")
            mediaRecorder?.start()
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            
            Log.d(TAG, "Recording started successfully! StartTime: $recordingStartTime")
            
            startNotificationUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "ERROR starting recording: ${e.message}")
            e.printStackTrace()
            cleanup()
        }
    }

    private fun createOutputFile(): File {
        val filesDir = filesDir
        android.util.Log.d(TAG, "Files dir: ${filesDir.absolutePath}")
        
        val screenRecordDir = File(filesDir, "screen_record")
        if (!screenRecordDir.exists()) {
            val created = screenRecordDir.mkdirs()
            android.util.Log.d(TAG, "Directory created: $created, exists: ${screenRecordDir.exists()}")
        }
        
        val timestamp = System.currentTimeMillis()
        val outputFile = File(screenRecordDir, "screen_$timestamp.mp4")
        
        try {
            val canWrite = outputFile.createNewFile()
            android.util.Log.d(TAG, "Output file created: $canWrite, path: ${outputFile.absolutePath}")
            if (outputFile.exists()) {
                outputFile.delete()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error creating output file: ${e.message}")
        }
        
        android.util.Log.d(TAG, "Final output file: ${outputFile.absolutePath}")
        
        return outputFile
    }

    private fun startNotificationUpdate() {
        notificationUpdateRunnable = object : Runnable {
            override fun run() {
                if (isRecording) {
                    val elapsed = System.currentTimeMillis() - recordingStartTime
                    val seconds = (elapsed / 1000) % 60
                    val minutes = (elapsed / 1000) / 60
                    val timeText = String.format("%02d:%02d", minutes, seconds)
                    
                    val notificationManager = getSystemService(NotificationManager::class.java)
                    val notification = NotificationCompat.Builder(this@ScreenRecordService, CHANNEL_ID)
                        .setContentTitle(getString(R.string.recording_title))
                        .setContentText("${getString(R.string.recording_in_progress)} $timeText")
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setOngoing(true)
                        .setSilent(true)
                        .build()
                    
                    notificationManager.notify(NOTIFICATION_ID, notification)
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(notificationUpdateRunnable!!)
    }

    fun stopRecording(): File? {
        Log.d(TAG, "=== stopRecording called ===")
        if (!isRecording) {
            Log.d(TAG, "Not recording, returning null")
            return null
        }

        isRecording = false
        notificationUpdateRunnable?.let { handler.removeCallbacks(it) }

        val tempFile = outputFile
        var savedFile: File? = null
        
        Log.d(TAG, "tempFile exists: ${tempFile?.exists()}, path: ${tempFile?.absolutePath}")
        
        try {
            Log.d(TAG, "Calling mediaRecorder.stop()...")
            mediaRecorder?.stop()
            Log.d(TAG, "mediaRecorder.stop() success!")
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {
            Log.e(TAG, "ERROR during stop: ${e.message}")
            e.printStackTrace()
            outputFile?.delete()
            outputFile = null
            return null
        }

        Log.d(TAG, "Saving to public directory...")
        tempFile?.let { file ->
            if (file.exists()) {
                Log.d(TAG, "Source file exists, size: ${file.length()} bytes")
                savedFile = saveToPublicDirectory(file)
            } else {
                Log.e(TAG, "Source file does NOT exist!")
            }
        }

        virtualDisplay?.release()
        virtualDisplay = null

        // Only stop MediaProjection if in reauth mode
        // In reuse mode, keep the MediaProjection for next recording
        if (recordingMode == MODE_REAUTH) {
            Log.d(TAG, "Mode REAUTH: stopping MediaProjection")
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
            savedResultCode = 0
            savedData = null
        } else {
            Log.d(TAG, "Mode REUSE: keeping MediaProjection for next recording")
        }

        outputFile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        
        recordingCallback?.onRecordingStopped()
        
        return savedFile
    }
    
    private fun saveToPublicDirectory(sourceFile: File): File? {
        Log.d(TAG, "saveToPublicDirectory called")
        val timestamp = System.currentTimeMillis()
        val fileName = "ScreenRecord_$timestamp.mp4"
        
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenRecord")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri == null) {
            Log.e(TAG, "Failed to create MediaStore entry!")
            return null
        }
        
        Log.d(TAG, "MediaStore URI created: $uri")

        return try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            
            sourceFile.delete()
            
            Log.d(TAG, "Video saved to: $uri")
            Log.d(TAG, "Video path: ${Environment.DIRECTORY_MOVIES}/ScreenRecord/$fileName")
            
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ScreenRecord/$fileName")
        } catch (e: Exception) {
            Log.e(TAG, "ERROR saving to public directory: ${e.message}")
            e.printStackTrace()
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun cleanup() {
        virtualDisplay?.release()
        virtualDisplay = null
        mediaRecorder?.release()
        mediaRecorder = null
        mediaProjection?.stop()
        mediaProjection = null
        outputFile?.delete()
        outputFile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun isRecording(): Boolean = isRecording

    fun getRecordingStartTime(): Long = recordingStartTime

    fun getOutputFile(): File? = outputFile

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
