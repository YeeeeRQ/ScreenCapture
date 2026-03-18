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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import com.example.capture.MainActivity
import com.example.capture.PermissionActivity
import com.example.capture.R
import com.example.capture.view.FloatingView
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    
    // MediaCodec for video encoding
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var encoderSurface: Surface? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    
    // OpenGL ES for screenshot
    private var surfaceTexture: SurfaceTexture? = null
    private var textureId: Int = 0
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
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
    
    // Shader and FBO
    private var shaderProgram: Int = 0
    private var fboId: Int = 0
    private var fboTextureId: Int = 0
    private var vertexBuffer: java.nio.FloatBuffer? = null
    private var texCoordBuffer: java.nio.FloatBuffer? = null
    
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
    
    // ============ EGL Methods ============
    
    private fun initEGLWithEncoder(): Boolean {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                Log.e(TAG, "Unable to get EGL display")
                return false
            }
            
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                Log.e(TAG, "Unable to initialize EGL")
                return false
            }
            
            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
            
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
                Log.e(TAG, "Unable to choose EGL config")
                return false
            }
            
            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "Unable to create EGL context")
                return false
            }
            
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "Unable to create EGL window surface")
                return false
            }
            
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                Log.e(TAG, "Unable to make EGL context current")
                return false
            }
            
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            
            Log.d(TAG, "EGL with encoder initialized")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "EGL init error: ${e.message}")
            return false
        }
    }
    
    private fun releaseEGL() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (e: Exception) {
            Log.e(TAG, "EGL release error: ${e.message}")
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }
    
    // ============ Shader Methods ============
    
    private fun createShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
    
    private fun createProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = createShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        val fragmentShader = createShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
        
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e(TAG, "Program link error: ${GLES20.glGetProgramInfoLog(program)}")
            return 0
        }
        
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        
        return program
    }
    
    private fun setupGeometry() {
        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val texCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer?.position(0)
        
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoords)
        texCoordBuffer?.position(0)
    }
    
    private fun drawTexture(texId: Int) {
        GLES20.glUseProgram(shaderProgram)
        
        val positionHandle = GLES20.glGetAttribLocation(shaderProgram, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(shaderProgram, "aTextureCoord")
        val textureHandle = GLES20.glGetUniformLocation(shaderProgram, "sTexture")
        
        vertexBuffer?.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        
        texCoordBuffer?.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(textureHandle, 0)
        
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }
    
    // ============ FBO Methods ============
    
    private fun createFBO() {
        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        fboId = fboIds[0]
        
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        fboTextureId = texIds[0]
        
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, screenWidth, screenHeight, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTextureId, 0)
        
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO not complete: $status")
        }
        
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        Log.d(TAG, "FBO created: fboId=$fboId")
    }
    
    private fun releaseFBO() {
        if (fboId != 0) {
            val fboIds = intArrayOf(fboId)
            GLES20.glDeleteFramebuffers(1, fboIds, 0)
            fboId = 0
        }
        if (fboTextureId != 0) {
            val texIds = intArrayOf(fboTextureId)
            GLES20.glDeleteTextures(1, texIds, 0)
            fboTextureId = 0
        }
    }
    
    private fun captureSnapshot(): Bitmap? {
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glViewport(0, 0, screenWidth, screenHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            
            drawTexture(textureId)
            
            val buffer = ByteBuffer.allocateDirect(screenWidth * screenHeight * 4)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            GLES20.glReadPixels(0, 0, screenWidth, screenHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            
            buffer.rewind()
            val bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            
            val matrix = android.graphics.Matrix()
            matrix.preScale(1f, -1f)
            val flipped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight, matrix, true)
            bitmap.recycle()
            
            return flipped
        } catch (e: Exception) {
            Log.e(TAG, "captureSnapshot error: ${e.message}")
            return null
        }
    }
    
    private fun saveBitmapToMediaStore(bitmap: Bitmap): Boolean {
        return try {
            val filename = "Screen_${System.currentTimeMillis()}.png"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScreenRecord")
            }
            
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
                Log.d(TAG, "Screenshot saved: $uri")
                true
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Save bitmap error: ${e.message}")
            false
        }
    }
    
    // ============ Render Thread ============
    
    private fun startRenderThread() {
        renderThread = Thread {
            try {
                Log.d(TAG, "Render thread starting...")
                
                if (!initEGLWithEncoder()) {
                    Log.e(TAG, "Failed to init EGL")
                    return@Thread
                }
                
                shaderProgram = createProgram(vertexShaderCode, fragmentShaderCode)
                if (shaderProgram == 0) {
                    Log.e(TAG, "Failed to create shader")
                    return@Thread
                }
                
                setupGeometry()
                createFBO()
                
                isRendering = true
                Log.d(TAG, "Render thread ready")
                
                while (isRecording && !Thread.interrupted()) {
                    try {
                        surfaceTexture?.updateTexImage()
                        
                        drawToEncoder()
                        
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
    
    private fun drawToEncoder() {
        try {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            GLES20.glViewport(0, 0, screenWidth, screenHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawTexture(textureId)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: Exception) {
            Log.e(TAG, "drawToEncoder error: ${e.message}")
        }
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

    fun takeScreenshot(): Boolean {
        if (!isRecording) {
            Log.w(TAG, "Cannot take screenshot: not recording")
            return false
        }
        
        Log.d(TAG, "Taking screenshot...")
        
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
                        val saved = saveBitmapToMediaStore(bitmap)
                        if (saved) {
                            activityRef?.get()?.runOnUiThread {
                                android.widget.Toast.makeText(activityRef?.get(), "截图已保存", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            bitmap.recycle()
                            return true
                        }
                        bitmap.recycle()
                    }
                }
            }
            waitCount++
        }
        
        Log.e(TAG, "Screenshot timeout")
        return false
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
            // 先隐藏并释放旧的实例
            FloatingView.release()
            
            // 创建新的单例实例
            floatingView = FloatingView.getInstance(this)
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

        Log.d(TAG, "=== startRecordingInternal: MediaCodec mode ===")

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
            
            isRecording = true
            
            startRenderThread()
            
            Thread.sleep(500)
            
            val inputSurface = Surface(surfaceTexture)
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
            
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            
            startNotificationUpdate()
            recordingCallback?.onRecordingStarted()
            
            Log.d(TAG, "Recording started!")
            
        } catch (e: Exception) {
            Log.e(TAG, "ERROR starting recording: ${e.message}")
            e.printStackTrace()
            recordingCallback?.onRecordingError(e.message ?: "Unknown error")
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
            Log.d(TAG, "Releasing GL resources...")
            releaseFBO()
            
            if (shaderProgram != 0) {
                GLES20.glDeleteProgram(shaderProgram)
                shaderProgram = 0
            }
            
            vertexBuffer?.clear()
            texCoordBuffer?.clear()
            vertexBuffer = null
            texCoordBuffer = null
            
            releaseEGL()
            
            surfaceTexture?.release()
            surfaceTexture = null
            
            if (textureId != 0) {
                val textures = intArrayOf(textureId)
                GLES20.glDeleteTextures(1, textures, 0)
                textureId = 0
            }
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
        
        releaseFBO()
        releaseEGL()
        surfaceTexture?.release()
        surfaceTexture = null
        
        if (textureId != 0) {
            val textures = intArrayOf(textureId)
            GLES20.glDeleteTextures(1, textures, 0)
            textureId = 0
        }
        
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
