package com.example.capture.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
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
import com.example.capture.service.RecordingState
import com.example.capture.service.RecordingStateListener
import com.example.capture.service.ScreenRecordService
import java.lang.ref.WeakReference

class FloatingView private constructor(
    private val context: Context,
    private val serviceTokenProvider: () -> IBinder?
) : RecordingStateListener {

    companion object {
        private const val TAG = "FloatingView"

        fun create(
            context: Context,
            serviceTokenProvider: () -> IBinder?
        ): FloatingView {
            return FloatingView(context, serviceTokenProvider)
        }
    }

    private var windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatingView: View? = null
    private var floatingImage: ImageView? = null
    private var floatingScreenshot: ImageView? = null
    private var floatingScreenshotProgress: ProgressBar? = null
    private var floatingTimeText: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var serviceRef: WeakReference<ScreenRecordService>? = null

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

    private var isViewAttached: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val service: ScreenRecordService?
        get() {
            val binder = serviceTokenProvider() ?: return null
            val method = binder.javaClass.getMethod("getService")
            return method.invoke(binder) as? ScreenRecordService
        }

    override fun onRecordingStateChanged(state: RecordingState) {
        _isRecording = state.isRecording
        _isTakingScreenshot = state.isTakingScreenshot
        mainHandler.post {
            updateView()
            if (state.isRecording) {
                updateRecordingTime(state.recordingTime)
            }
        }
    }

    fun show() {
        if (floatingView == null) {
            val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            floatingView = inflater.inflate(R.layout.floating_view, null)
            floatingImage = floatingView?.findViewById(R.id.floating_image)
            floatingScreenshot = floatingView?.findViewById(R.id.floating_screenshot)
            floatingScreenshotProgress = floatingView?.findViewById(R.id.floating_screenshot_progress)
            floatingTimeText = floatingView?.findViewById(R.id.floating_time)

            floatingScreenshot?.setOnClickListener {
                Log.d(TAG, "Screenshot button clicked!")
                floatingScreenshot?.performHapticFeedback(
                    android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
                service?.takeScreenshot()
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

            updateView()
        }

        floatingView?.let { view ->
            if (!isViewAttached) {
                try {
                    windowManager.addView(view, params)
                    isViewAttached = true
                } catch (e: Exception) {
                    Log.d(TAG, "View may already be added: ${e.message}")
                }
            }
        }
    }

    fun hide() {
        floatingView?.let {
            if (isViewAttached) {
                try {
                    windowManager.removeView(it)
                    isViewAttached = false
                } catch (e: Exception) {
                    Log.d(TAG, "View remove error: ${e.message}")
                }
            }
        }
    }

    fun release() {
        hide()
        service?.removeRecordingStateListener(this)
        serviceRef?.clear()
        serviceRef = null
        floatingView = null
        floatingImage = null
        floatingScreenshot = null
        floatingScreenshotProgress = null
        floatingTimeText = null
        params = null
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

    fun updateRecordingState(state: RecordingState) {
        _isRecording = state.isRecording
        _isTakingScreenshot = state.isTakingScreenshot
        updateView()
        if (state.isRecording) {
            updateRecordingTime(state.recordingTime)
        }
    }

    fun bindService(service: ScreenRecordService) {
        serviceRef?.clear()
        serviceRef = WeakReference(service)
        service.addRecordingStateListener(this)
        val state = service.recordingState.value
        _isRecording = state.isRecording
        _isTakingScreenshot = state.isTakingScreenshot
        updateView()
        if (_isRecording) {
            updateRecordingTime(state.recordingTime)
        }
        Log.d(TAG, "Service bound, isRecording=$_isRecording")
    }

    fun unbindService() {
        service?.removeRecordingStateListener(this)
        serviceRef?.clear()
        serviceRef = null
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

                params?.let {
                    prefs.edit()
                        .putInt("position_x", it.x)
                        .putInt("position_y", it.y)
                        .apply()
                }

                if (deltaX < 30 && deltaY < 30) {
                    floatingView?.performHapticFeedback(
                        android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                    )
                    service?.toggleRecording()
                }
                true
            }
            else -> false
        }
    }
}