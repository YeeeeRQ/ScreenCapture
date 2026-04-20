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
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.capture.MainActivity
import com.example.capture.PermissionActivity
import com.example.capture.R
import com.example.capture.view.FloatingView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScreenRecordService : Service() {

    companion object {
        private const val TAG = RecordingConstants.TAG
        const val CHANNEL_ID = RecordingConstants.CHANNEL_ID
        const val NOTIFICATION_ID = RecordingConstants.NOTIFICATION_ID
        const val ACTION_START = RecordingConstants.ACTION_START
        const val ACTION_STOP = RecordingConstants.ACTION_STOP
        const val ACTION_SCREENSHOT = RecordingConstants.ACTION_SCREENSHOT
        const val ACTION_STATUS = RecordingConstants.ACTION_STATUS

        const val BROADCAST_AUTHORIZATION_RESULT = RecordingConstants.BROADCAST_AUTHORIZATION_RESULT
        const val BROADCAST_SCREENSHOT_RESULT = RecordingConstants.BROADCAST_SCREENSHOT_RESULT
        const val BROADCAST_STATUS_RESULT = RecordingConstants.BROADCAST_STATUS_RESULT

        const val VIDEO_WIDTH = RecordingConstants.VIDEO_WIDTH
        const val VIDEO_HEIGHT = RecordingConstants.VIDEO_HEIGHT
        const val VIDEO_BIT_RATE = RecordingConstants.VIDEO_BIT_RATE
        const val VIDEO_FRAME_RATE = RecordingConstants.VIDEO_FRAME_RATE

        const val MODE_REAUTH = RecordingConstants.MODE_REAUTH
        const val MODE_REUSE = RecordingConstants.MODE_REUSE
    }

    private val _recordingState = MutableStateFlow(RecordingState())
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private var recordingMode = MODE_REAUTH

    private var savedResultCode: Int = 0
    private var savedData: Intent? = null
    
    private val prefs by lazy {
        getSharedPreferences("screen_record_prefs", Context.MODE_PRIVATE)
    }
    
    private val binder = LocalBinder()
    private var floatingView: FloatingView? = null

    private val stateListeners = mutableListOf<RecordingStateListener>()

    private val notificationHelper by lazy { NotificationHelper(this) }

    fun addRecordingStateListener(listener: RecordingStateListener) {
        stateListeners.add(listener)
    }

    fun removeRecordingStateListener(listener: RecordingStateListener) {
        stateListeners.remove(listener)
    }

    private fun notifyRecordingStateChanged(state: RecordingState) {
        stateListeners.forEach { it.onRecordingStateChanged(state) }
    }

    private fun updateRecordingState(state: RecordingState) {
        _recordingState.value = state
        notifyRecordingStateChanged(state)
    }
    
    fun setFloatingView(view: FloatingView?) {
        floatingView = view
    }
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var outputFile: File? = null

    // MediaCodec for video encoding
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var encoderSurface: Surface? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false

    // OpenGL ES for screenshot
    private var surfaceTexture: SurfaceTexture? = null
    private var textureId: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    // Render thread
    private var renderThread: Thread? = null
    private var isRendering = false

    // Screenshot sync
    private val snapshotLock = Object()
    private var needSnapshot = false
    private var pendingSnapshot: Bitmap? = null

    // EncoderManager for EGL/GL operations
    private var encoderManager: EncoderManager? = null

    // Shader code
    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = aPosition;
            vTextureCoord = aTextureCoord;
        }
    """.trimIndent()
    
    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """.trimIndent()
    
    // ============ EGL/Encoder Methods (delegated to EncoderManager) ============

    private fun initEncoder(): Boolean {
        encoderManager = EncoderManager(screenWidth, screenHeight)
        return encoderManager?.init(encoderSurface!!, textureId) ?: false
    }

    private fun releaseEncoder() {
        encoderManager?.release()
        encoderManager = null
    }

    private fun drawFrame() {
        encoderManager?.drawFrame(textureId)
    }

    private fun captureSnapshot(): Bitmap? {
        return encoderManager?.captureScreenshot(textureId)
    }

    private fun saveBitmapToMediaStore(bitmap: Bitmap): Pair<Boolean, String?> {
        return try {
            val filename = "Screen_${System.currentTimeMillis()}.png"
            val relativePath = "Pictures/ScreenRecord"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
                val filePath = "$relativePath/$filename"
                Log.d(TAG, "Screenshot saved: $filePath")
                Pair(true, filePath)
            } ?: Pair(false, null)
        } catch (e: Exception) {
            Log.e(TAG, "Save bitmap error: ${e.message}")
            Pair(false, null)
        }
    }

    // ============ Render Thread ============

    private val WARMUP_FRAMES = 15
    private var frameCount = 0

    private fun startRenderThread() {
        frameCount = 0
        renderThread = Thread {
            try {
                Log.d(TAG, "Render thread starting...")

                if (!initEncoder()) {
                    Log.e(TAG, "Failed to init EncoderManager")
                    return@Thread
                }

                isRendering = true
                Log.d(TAG, "Render thread ready, starting render loop")

                while (isRecording && !Thread.interrupted()) {
                    try {
                        surfaceTexture?.updateTexImage()

                        if (frameCount < WARMUP_FRAMES) {
                            frameCount++
                            drainEncoder()
                            Thread.sleep(33)
                            continue
                        }

                        drawFrame()

                        synchronized(snapshotLock) {
                            if (needSnapshot && pendingSnapshot == null) {
                                pendingSnapshot = captureSnapshot()
                                needSnapshot = false
                            }
                        }

                        drainEncoder()
                        Thread.sleep(33)
                    } catch (e: Exception) {
                        if (isRecording) Log.e(TAG, "Render error: ${e.message}")
                    }
                }

                Log.d(TAG, "Render thread exiting")
            } catch (e: Exception) {
                Log.e(TAG, "Render thread error: ${e.message}")
            }
        }
        renderThread?.start()
    }

    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val idx = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: break
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = mediaCodec?.outputFormat
                    Log.d(TAG, "Encoder format: $fmt")
                    videoTrackIndex = mediaMuxer?.addTrack(fmt!!) ?: -1
                    mediaMuxer?.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val data = mediaCodec?.getOutputBuffer(idx)
                    if (muxerStarted && bufferInfo.size > 0 && data != null) {
                        data.position(bufferInfo.offset)
                        data.limit(bufferInfo.offset + bufferInfo.size)
                        try {
                            mediaMuxer?.writeSampleData(videoTrackIndex, data, bufferInfo)
                        } catch (e: Exception) {
                            Log.e(TAG, "writeSampleData error: ${e.message}")
                        }
                    }
                    mediaCodec?.releaseOutputBuffer(idx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }
    
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

    fun takeScreenshot(): Boolean {
        if (!isRecording) {
            Log.w(TAG, "Cannot take screenshot: not recording")
            return false
        }
        
        Log.d(TAG, "Taking screenshot...")
        
        updateRecordingState(_recordingState.value.copy(isTakingScreenshot = true))
        
        handler.postDelayed({
            synchronized(snapshotLock) {
                needSnapshot = true
                pendingSnapshot = null
            }
            
            var waitCount = 0
            while (waitCount < 50) {
                Thread.sleep(10)
                synchronized(snapshotLock) {
                    if (pendingSnapshot != null) {
                        val bitmap = pendingSnapshot
                        pendingSnapshot = null
                        
                        if (bitmap != null) {
                            val (saved, filePath) = saveBitmapToMediaStore(bitmap)
                            updateRecordingState(_recordingState.value.copy(isTakingScreenshot = false))
                            if (saved && filePath != null) {
                                activityRef?.get()?.runOnUiThread {
                                    android.widget.Toast.makeText(activityRef?.get(), "截图已保存", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                sendScreenshotBroadcast(true, filePath)
                            } else {
                                sendScreenshotBroadcast(false, error = "Failed to save screenshot")
                            }
                            bitmap.recycle()
                            return@postDelayed
                        }
                    }
                }
                waitCount++
            }
            
            Log.e(TAG, "Screenshot timeout")
            updateRecordingState(_recordingState.value.copy(isTakingScreenshot = false))
            sendScreenshotBroadcast(false, error = "Screenshot timeout")
        }, 300)
        
        return true
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
        
        when (intent?.action) {
            ACTION_START -> {
                var resultCode = intent.getIntExtra("result_code", Activity.RESULT_CANCELED)
                var data = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("data", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("data")
                }
                
                // Fallback to PermissionActivity static variables if not in intent
                if (resultCode == Activity.RESULT_CANCELED || data == null) {
                    Log.d(TAG, "Intent extras not found, falling back to static variables")
                    resultCode = PermissionActivity.pendingResultCode
                    data = PermissionActivity.pendingData
                    PermissionActivity.pendingResultCode = -1
                    PermissionActivity.pendingData = null
                }
                
                Log.d(TAG, "Start recording: resultCode=$resultCode, data=${data != null}")
                
                if (resultCode == Activity.RESULT_OK && data != null) {
                    saveProjectionData(resultCode, data)
                    startRecordingInternal(resultCode, data)
                    
                    showFloatingView()
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stopping recording via ACTION_STOP")
                stopRecording()
            }
            ACTION_SCREENSHOT -> {
                Log.d(TAG, "Screenshot requested via Intent")
                if (!takeScreenshot()) {
                    sendScreenshotBroadcast(false, error = "Not recording")
                }
            }
            ACTION_STATUS -> {
                Log.d(TAG, "Status requested via Intent")
                sendStatusBroadcast()
            }
        }
        
        return START_STICKY
    }

    private fun sendScreenshotBroadcast(success: Boolean, filePath: String? = null, error: String? = null) {
        val intent = Intent(BROADCAST_SCREENSHOT_RESULT).apply {
            putExtra("success", success)
            if (filePath != null) {
                putExtra("file_path", filePath)
            }
            if (error != null) {
                putExtra("error", error)
            }
        }
        Log.d(TAG, "Sending SCREENSHOT_RESULT broadcast: success=$success, path=$filePath, error=$error")
        sendBroadcast(intent)
    }

    private fun sendStatusBroadcast() {
        val state = _recordingState.value
        val intent = Intent(BROADCAST_STATUS_RESULT).apply {
            putExtra("isRecording", state.isRecording)
            putExtra("recordingTime", state.recordingTime)
        }
        Log.d(TAG, "Sending STATUS_RESULT broadcast: isRecording=${state.isRecording}, time=${state.recordingTime}")
        sendBroadcast(intent)
    }
    
    private fun showFloatingView() {
        try {
            if (floatingView == null) {
                floatingView = FloatingView.getInstance(applicationContext)
            }
            floatingView?.let { fv ->
                fv.setService(this)
                fv.show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing FloatingView: ${e.message}")
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        notificationHelper.createNotificationChannel()
    }

    private fun startRecordingInternal(resultCode: Int, data: Intent) {
        if (isRecording) return

        Log.d(TAG, "=== startRecordingInternal: MediaCodec mode ===")

        val notification = notificationHelper.createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        mediaProjection?.registerCallback(projectionCallback, handler)

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        
        Log.d(TAG, "Screen: ${screenWidth}x${screenHeight}, density: $screenDensity")

        try {
            outputFile = createOutputFile()
            
            mediaMuxer = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }
            
            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoderSurface = createInputSurface()
                start()
            }
            
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            
            surfaceTexture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(screenWidth, screenHeight)
            }
            
            // Pre-warm encoder before starting VirtualDisplay
            Log.d(TAG, "Pre-warming encoder...")
            for (i in 1..10) {
                try {
                    surfaceTexture?.updateTexImage()
                    drawFrame()
                    drainEncoder()
                    Thread.sleep(33)
                } catch (e: Exception) {
                    Log.e(TAG, "Pre-warm error: ${e.message}")
                }
            }
            Log.d(TAG, "Pre-warm complete")
            
            val inputSurface = Surface(surfaceTexture)

            isRecording = true

            startRenderThread()

            Thread.sleep(100)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                handler
            )

            recordingStartTime = System.currentTimeMillis()
            updateRecordingState(RecordingState(
                isRecording = true,
                recordingStartTime = recordingStartTime
            ))

            startNotificationUpdate()

            Log.d(TAG, "Recording started!")

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

                    updateRecordingState(_recordingState.value.copy(
                        recordingTime = timeText
                    ))

                    val notificationManager = getSystemService(NotificationManager::class.java)
                    val notification = notificationHelper.createRecordingNotification(timeText)

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
        updateRecordingState(RecordingState())
        notificationUpdateRunnable?.let { handler.removeCallbacks(it) }

        val tempFile = outputFile
        var savedFile: File? = null
        
        Log.d(TAG, "Stopping render thread...")
        
        try {
            renderThread?.join(2000)
        } catch (e: Exception) {
            Log.e(TAG, "Error joining render thread: ${e.message}")
        }
        renderThread = null
        isRendering = false
        
        try {
            Log.d(TAG, "Signaling EOS...")
            mediaCodec?.signalEndOfInputStream()
            drainEncoder()
        } catch (e: Exception) {
            Log.e(TAG, "Error signaling EOS: ${e.message}")
        }
        
        try {
            Log.d(TAG, "Stopping muxer...")
            if (muxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
            mediaMuxer = null
            muxerStarted = false
            videoTrackIndex = -1
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping muxer: ${e.message}")
        }
        
        try {
            Log.d(TAG, "Releasing encoder...")
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            encoderSurface?.release()
            encoderSurface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder: ${e.message}")
        }
        
        try {
            Log.d(TAG, "Releasing VirtualDisplay...")
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VirtualDisplay: ${e.message}")
        }

        try {
            Log.d(TAG, "Releasing encoder resources...")
            releaseEncoder()
            surfaceTexture?.release()
            surfaceTexture = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing GL: ${e.message}")
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

        if (recordingMode == MODE_REAUTH) {
            Log.d(TAG, "Mode REAUTH: stopping MediaProjection")
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
            savedResultCode = 0
            savedData = null
        } else {
            Log.d(TAG, "Mode REUSE: keeping MediaProjection")
        }

        outputFile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

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
        isRecording = false
        
        try {
            renderThread?.join(1000)
        } catch (e: Exception) { }
        renderThread = null
        isRendering = false

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            encoderSurface?.release()
            encoderSurface = null
        } catch (e: Exception) { }

        try {
            if (muxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
            mediaMuxer = null
            muxerStarted = false
        } catch (e: Exception) { }

        virtualDisplay?.release()
        virtualDisplay = null

        releaseEncoder()
        surfaceTexture?.release()
        surfaceTexture = null

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
