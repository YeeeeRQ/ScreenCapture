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
import android.widget.ProgressBar
import android.widget.TextView
import com.example.capture.R
import com.example.capture.service.RecordingServiceInterface
import com.example.capture.service.RecordingState
import com.example.capture.service.RecordingStateListener

class FloatingView private constructor(private val context: Context) : RecordingStateListener {

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
            instance?.let { fv ->
                fv.screenRecordService?.removeRecordingStateListener(fv)
                fv.hide()
            }
            instance = null
        }
    }

    private var windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatingView: View? = null
    private var floatingImage: ImageView? = null
    private var floatingScreenshot: ImageView? = null
    private var floatingScreenshotProgress: ProgressBar? = null
    private var floatingTimeText: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenRecordService: RecordingServiceInterface? = null
    private var serviceRetryHandler: Handler? = null
    private var serviceRetryRunnable: Runnable? = null

    private val prefs by lazy {
        context.getSharedPreferences("floating_view_prefs", Context.MODE_PRIVATE)
    }

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var lastAction: Int = 0

    private var _isRecording: Boolean = false
    private var _isTakingScreenshot: Boolean = false

    fun updateRecordingState(state: RecordingState) {
        _isRecording = state.isRecording
        _isTakingScreenshot = state.isTakingScreenshot
        updateView()
        if (state.isRecording) {
            updateRecordingTime(state.recordingTime)
        }
    }

    fun setService(service: RecordingServiceInterface?) {
        if (screenRecordService != null && service != screenRecordService) {
            screenRecordService?.removeRecordingStateListener(this)
        }
        screenRecordService = service
        if (service != null) {
            stopServiceRetryTimer()
            service.addRecordingStateListener(this)

            if (floatingImage == null && floatingView != null) {
                show()
            }

            val state = service.recordingState.value
            _isRecording = state.isRecording
            _isTakingScreenshot = state.isTakingScreenshot
            updateView()
            if (_isRecording) {
                updateRecordingTime(state.recordingTime)
            }
        }
    }

    override fun onRecordingStateChanged(state: RecordingState) {
        Log.d(TAG, "onRecordingStateChanged: isRecording=${state.isRecording}, floatingImage=${floatingImage != null}")
        _isRecording = state.isRecording
        _isTakingScreenshot = state.isTakingScreenshot

        if (floatingImage == null && floatingView != null) {
            Log.d(TAG, "Calling show() from onRecordingStateChanged")
            show()
        }

        updateView()
        if (state.isRecording) {
            updateRecordingTime(state.recordingTime)
        }
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
        val shouldReinitialize = floatingView == null || floatingImage == null
        Log.d(TAG, "show() called: shouldReinitialize=$shouldReinitialize, floatingView=${floatingView != null}, floatingImage=${floatingImage != null}")

        if (shouldReinitialize) {
            floatingView?.let { oldView ->
                try {
                    windowManager.removeView(oldView)
                    Log.d(TAG, "Removed old view from window")
                } catch (e: Exception) {
                    Log.d(TAG, "Could not remove old view: ${e.message}")
                }
            }
            floatingView = null

            val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            floatingView = inflater.inflate(R.layout.floating_view, null)
            floatingImage = floatingView?.findViewById(R.id.floating_image)
            floatingScreenshot = floatingView?.findViewById(R.id.floating_screenshot)
            floatingScreenshotProgress = floatingView?.findViewById(R.id.floating_screenshot_progress)
            floatingTimeText = floatingView?.findViewById(R.id.floating_time)
            Log.d(TAG, "Inflated new view, floatingImage=${floatingImage != null}")

            floatingScreenshot?.setOnClickListener {
                screenRecordService?.takeScreenshot()
            }

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
        }

        updateView()

        floatingView?.let { view ->
            try {
                windowManager.addView(view, params)
                Log.d(TAG, "View added to window manager")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add view: ${e.message}")
            }
        }
    }
    
    fun hide() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun updateView() {
        val iconRes = if (_isRecording) R.drawable.ic_stop else R.drawable.ic_record
        floatingImage?.setImageResource(iconRes)
        floatingImage?.setColorFilter(Color.WHITE)
        floatingTimeText?.visibility = if (_isRecording) View.VISIBLE else View.GONE

        if (_isRecording) {
            if (_isTakingScreenshot) {
                floatingScreenshot?.visibility = View.GONE
                floatingScreenshotProgress?.visibility = View.VISIBLE
            } else {
                floatingScreenshot?.visibility = View.VISIBLE
                floatingScreenshotProgress?.visibility = View.GONE
            }
        } else {
            floatingScreenshot?.visibility = View.GONE
            floatingScreenshotProgress?.visibility = View.GONE
        }
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
                    // Haptic feedback for record button
                    floatingView?.performHapticFeedback(
                        android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                    )
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
}
