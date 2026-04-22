package com.example.capture.service

import android.content.Context
import android.content.Intent
import android.util.Log

class BroadcastHelper(private val context: Context) {

    companion object {
        private const val TAG = RecordingConstants.TAG
    }

    fun sendScreenshotBroadcast(success: Boolean, filePath: String? = null, error: String? = null) {
        val intent = Intent(RecordingConstants.BROADCAST_SCREENSHOT_RESULT).apply {
            putExtra("success", success)
            if (filePath != null) {
                putExtra("file_path", filePath)
            }
            if (error != null) {
                putExtra("error", error)
            }
        }
        Log.d(TAG, "Sending SCREENSHOT_RESULT broadcast: success=$success, path=$filePath, error=$error")
        context.sendBroadcast(intent)
    }

    fun sendStatusBroadcast(isRecording: Boolean, recordingTime: String) {
        val intent = Intent(RecordingConstants.BROADCAST_STATUS_RESULT).apply {
            putExtra("isRecording", isRecording)
            putExtra("recordingTime", recordingTime)
        }
        Log.d(TAG, "Sending STATUS_RESULT broadcast: isRecording=$isRecording, time=$recordingTime")
        context.sendBroadcast(intent)
    }
}