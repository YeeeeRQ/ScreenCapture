package com.example.capture.service

import android.content.Intent
import com.example.capture.view.FloatingView
import kotlinx.coroutines.flow.StateFlow

interface RecordingServiceInterface {
    val recordingState: StateFlow<RecordingState>

    fun saveProjectionData(resultCode: Int, data: Intent)
    fun setRecordingMode(mode: String)
    fun setFloatingView(view: FloatingView?)
    fun addRecordingStateListener(listener: RecordingStateListener)
    fun removeRecordingStateListener(listener: RecordingStateListener)
    fun takeScreenshot(): Boolean
    fun toggleRecording()
}