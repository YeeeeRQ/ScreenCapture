package com.example.capture.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.capture.MainActivity
import com.example.capture.PermissionActivity
import com.example.capture.R
import com.example.capture.view.FloatingView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScreenRecordService : Service(), RecordingServiceInterface {

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
    override val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private var recordingMode = MODE_REAUTH

    private val prefs by lazy {
        getSharedPreferences("screen_record_prefs", Context.MODE_PRIVATE)
    }

    private val binder = LocalBinder()
    private var floatingView: FloatingView? = null

    private val stateListeners = mutableListOf<RecordingStateListener>()

    private val notificationHelper by lazy { NotificationHelper(this) }
    private val fileManager by lazy { FileManager(this) }
    private val broadcastHelper by lazy { BroadcastHelper(this) }
    private val projectionHelper by lazy { ProjectionHelper(this) }
    private val notificationTracker by lazy {
        RecordingNotificationTracker(this, notificationHelper)
    }

    override fun addRecordingStateListener(listener: RecordingStateListener) {
        stateListeners.add(listener)
    }

    override fun removeRecordingStateListener(listener: RecordingStateListener) {
        stateListeners.remove(listener)
    }

    private fun notifyRecordingStateChanged(state: RecordingState) {
        stateListeners.forEach { it.onRecordingStateChanged(state) }
    }

    private fun updateRecordingState(state: RecordingState) {
        _recordingState.value = state
        notifyRecordingStateChanged(state)
    }

    override fun setFloatingView(view: FloatingView?) {
        floatingView = view
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var isRecording = false
    private var outputFile: File? = null

    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var encoderSurface: Surface? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false

    private var surfaceTexture: SurfaceTexture? = null
    private var textureId: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Int = 0

    private var renderThread: Thread? = null
    private var isRendering = false

    private val snapshotLock = Object()
    private var needSnapshot = false
    private var pendingSnapshot: Bitmap? = null

    private var encoderManager: EncoderManager? = null

    private val WARMUP_FRAMES = 15
    private var frameCount = 0

    private val handler = Handler(Looper.getMainLooper())
    private var recordingStartTime: Long = 0

    private var activityRef: WeakReference<Activity>? = null
    private var pendingStartRecording = false

    fun setActivity(activity: Activity) {
        activityRef = WeakReference(activity)
        projectionHelper.setActivity(activity)
    }

    fun clearActivity() {
        activityRef = null
        projectionHelper.clearActivity()
    }

    override fun saveProjectionData(resultCode: Int, data: Intent) {
        projectionHelper.saveProjectionData(resultCode, data)

        if (pendingStartRecording && resultCode == Activity.RESULT_OK) {
            pendingStartRecording = false
            Log.d(TAG, "Auto-starting recording after permission granted")
            startRecordingInternal(resultCode, data)
        }
    }

    override fun setRecordingMode(mode: String) {
        recordingMode = mode
        Log.d(TAG, "Recording mode set to: $mode")
    }

    fun hasSavedProjectionData(): Boolean {
        return projectionHelper.hasSavedProjectionData()
    }

    override fun toggleRecording() {
        Log.d(TAG, "toggleRecording called, isRecording=$isRecording")

        if (isRecording) {
            stopRecording()
        } else {
            val savedData = projectionHelper.getSavedData()
            if (savedData != null && savedData.first == Activity.RESULT_OK && savedData.second != null) {
                Log.d(TAG, "Starting recording with saved data")
                startRecordingInternal(savedData.first, savedData.second!!)
            } else {
                Log.d(TAG, "No valid projection data, requesting permission directly")
                projectionHelper.requestMediaProjectionPermission()
            }
        }
    }

    override fun takeScreenshot(): Boolean {
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
                            val (saved, filePath) = fileManager.saveBitmapToMediaStore(bitmap)
                            updateRecordingState(_recordingState.value.copy(isTakingScreenshot = false))
                            if (saved && filePath != null) {
                                activityRef?.get()?.runOnUiThread {
                                    Toast.makeText(activityRef?.get(), "截图已保存", Toast.LENGTH_SHORT).show()
                                }
                                broadcastHelper.sendScreenshotBroadcast(true, filePath)
                            } else {
                                broadcastHelper.sendScreenshotBroadcast(false, error = "Failed to save screenshot")
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
            broadcastHelper.sendScreenshotBroadcast(false, error = "Screenshot timeout")
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
                var data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("data", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("data")
                }

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
                    broadcastHelper.sendScreenshotBroadcast(false, error = "Not recording")
                }
            }
            ACTION_STATUS -> {
                Log.d(TAG, "Status requested via Intent")
                sendStatusBroadcast()
            }
        }

        return START_STICKY
    }

    private fun sendStatusBroadcast() {
        val state = _recordingState.value
        broadcastHelper.sendStatusBroadcast(state.isRecording, state.recordingTime)
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
            outputFile = fileManager.createOutputFile()

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

            notificationTracker.onUpdateState = { timeText ->
                updateRecordingState(_recordingState.value.copy(recordingTime = timeText))
            }
            notificationTracker.startTracking(recordingStartTime)

            Log.d(TAG, "Recording started!")

        } catch (e: Exception) {
            Log.e(TAG, "ERROR starting recording: ${e.message}")
            e.printStackTrace()
            cleanup()
        }
    }

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

    fun stopRecording(): File? {
        Log.d(TAG, "=== stopRecording called ===")
        if (!isRecording) {
            Log.d(TAG, "Not recording, returning null")
            return null
        }

        isRecording = false
        updateRecordingState(RecordingState())
        notificationTracker.stopTracking()

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
                savedFile = fileManager.saveToPublicDirectory(file)
            } else {
                Log.e(TAG, "Source file does NOT exist!")
            }
        }

        if (recordingMode == MODE_REAUTH) {
            Log.d(TAG, "Mode REAUTH: stopping MediaProjection")
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
            projectionHelper.saveProjectionData(0, Intent())
        } else {
            Log.d(TAG, "Mode REUSE: keeping MediaProjection")
        }

        outputFile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        return savedFile
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