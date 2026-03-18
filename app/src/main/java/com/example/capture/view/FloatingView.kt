package com.example.capture.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.example.capture.R
import com.example.capture.service.ScreenRecordService

class FloatingView private constructor(private val context: Context) : ScreenRecordService.RecordingCallback {
    
    companion object {
        private const val TAG = "FloatingView"
        
        @Volatile
        private var instance: FloatingView? = null
        
        fun getInstance(context: Context): FloatingView {
            return instance ?: synchronized(this) {
                instance ?: FloatingView(context).also { instance = it }
            }
        }
        
        fun release() {
            instance?.hide()
            instance = null
        }
    }
    
    private var windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatingView: View? = null
    private var floatingImage: ImageView? = null
    private var floatingScreenshot: ImageView? = null
    private var floatingTimeText: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenRecordService: ScreenRecordService? = null
    private var serviceRetryHandler: Handler? = null
    private var serviceRetryRunnable: Runnable? = null
    private var timeUpdateHandler: Handler? = null
    private var timeUpdateRunnable: Runnable? = null
    private var recordingStartTime: Long = 0
    
    private val prefs by lazy {
        context.getSharedPreferences("floating_view_prefs", Context.MODE_PRIVATE)
    }
    
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var lastAction: Int = 0
    
    var isRecording: Boolean = false
        set(value) {
            field = value
            updateView()
        }
    
    fun setService(service: ScreenRecordService?) {
        screenRecordService = service
        service?.setCallback(this)
        if (service != null) {
            stopServiceRetryTimer()
            isRecording = service.isRecording()
            if (isRecording) {
                recordingStartTime = service.getRecordingStartTime()
                if (recordingStartTime > 0) {
                    startTimeUpdate()
                }
            }
            Log.d(TAG, "Service set successfully, isRecording=$isRecording")
        }
    }
    
    override fun onRecordingStarted() {
        Log.d(TAG, "onRecordingStarted callback")
        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        startTimeUpdate()
    }
    
    override fun onRecordingStopped() {
        Log.d(TAG, "onRecordingStopped callback")
        isRecording = false
        stopTimeUpdate()
    }
    
    override fun onRecordingError(error: String) {
        Log.d(TAG, "onRecordingError: $error")
        isRecording = false
    }
    
    private fun startServiceRetryTimer() {
        if (serviceRetryHandler == null) {
            serviceRetryHandler = Handler(Looper.getMainLooper())
        }
        serviceRetryRunnable = object : Runnable {
            override fun run() {
                if (screenRecordService == null) {
                    Log.d(TAG, "Service still null, retrying...")
                    startServiceRetryTimer()
                }
            }
        }
        serviceRetryHandler?.postDelayed(serviceRetryRunnable!!, 1000)
    }
    
    private fun stopServiceRetryTimer() {
        serviceRetryRunnable?.let { serviceRetryHandler?.removeCallbacks(it) }
        serviceRetryRunnable = null
    }
    
    fun show() {
        if (floatingView != null) return
        
        val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_view, null)
        floatingImage = floatingView?.findViewById(R.id.floating_image)
        floatingScreenshot = floatingView?.findViewById(R.id.floating_screenshot)
        floatingTimeText = floatingView?.findViewById(R.id.floating_time)
        
        // Screenshot button click
        floatingScreenshot?.setOnClickListener {
            Log.d(TAG, "Screenshot button clicked!")
            screenRecordService?.takeScreenshot()
        }
        
        // 读取保存的位置，如果没有保存则使用默认值
        val savedX = prefs.getInt("position_x", 100)
        val savedY = prefs.getInt("position_y", 300)
        
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }
        
        floatingView?.setOnTouchListener(touchListener)
        floatingView?.isClickable = true
        floatingView?.isFocusable = true
        
        updateView()
        
        try {
            windowManager.addView(floatingView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun hide() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            floatingView = null
        }
    }
    
    private fun updateView() {
        val iconRes = if (isRecording) R.drawable.ic_stop else R.drawable.ic_record
        floatingImage?.setImageResource(iconRes)
        floatingImage?.setColorFilter(Color.WHITE)
        floatingTimeText?.visibility = if (isRecording) View.VISIBLE else View.GONE
        floatingScreenshot?.visibility = if (isRecording) View.VISIBLE else View.GONE
    }
    
    fun updateRecordingTime(timeText: String) {
        floatingTimeText?.text = timeText
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private val touchListener = View.OnTouchListener { _, event ->
        Log.d(TAG, "Touch event: ${event.action}")
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params?.x ?: 0
                initialY = params?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                lastAction = event.action
                Log.d(TAG, "ACTION_DOWN: initialTouchX=$initialTouchX, initialTouchY=$initialTouchY")
                true
            }
            MotionEvent.ACTION_MOVE -> {
                params?.x = initialX + (event.rawX - initialTouchX).toInt()
                params?.y = initialY + (event.rawY - initialTouchY).toInt()
                windowManager.updateViewLayout(floatingView, params)
                true
            }
            MotionEvent.ACTION_UP -> {
                val deltaX = Math.abs(event.rawX - initialTouchX)
                val deltaY = Math.abs(event.rawY - initialTouchY)
                Log.d(TAG, "ACTION_UP: deltaX=$deltaX, deltaY=$deltaY, lastAction=$lastAction")
                
                // 保存位置
                params?.let {
                    prefs.edit()
                        .putInt("position_x", it.x)
                        .putInt("position_y", it.y)
                        .apply()
                }
                
                if (deltaX < 30 && deltaY < 30) {
                    if (screenRecordService != null) {
                        Log.d(TAG, "Calling toggleRecording!")
                        screenRecordService?.toggleRecording()
                    } else {
                        Log.w(TAG, "Service is null, cannot toggle recording!")
                    }
                }
                true
            }
            else -> false
        }
    }
    
    private fun startTimeUpdate() {
        if (timeUpdateHandler == null) {
            timeUpdateHandler = Handler(Looper.getMainLooper())
        }
        timeUpdateRunnable = object : Runnable {
            override fun run() {
                if (recordingStartTime > 0) {
                    val elapsed = System.currentTimeMillis() - recordingStartTime
                    val seconds = (elapsed / 1000) % 60
                    val minutes = (elapsed / 1000) / 60
                    val timeText = String.format("%02d:%02d", minutes, seconds)
                    updateRecordingTime(timeText)
                }
                timeUpdateHandler?.postDelayed(this, 1000)
            }
        }
        timeUpdateHandler?.post(timeUpdateRunnable!!)
        Log.d(TAG, "Time update started")
    }
    
    private fun stopTimeUpdate() {
        timeUpdateRunnable?.let {
            timeUpdateHandler?.removeCallbacks(it)
        }
        timeUpdateRunnable = null
        recordingStartTime = 0
        Log.d(TAG, "Time update stopped")
    }
}
