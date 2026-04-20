package com.example.capture.service

object RecordingConstants {
    const val TAG = "ScreenRecord"
    const val CHANNEL_ID = "screen_record_channel"
    const val NOTIFICATION_ID = 1
    const val ACTION_START = "com.example.capture.START"
    const val ACTION_STOP = "com.example.capture.STOP"
    const val ACTION_SCREENSHOT = "com.example.capture.SCREENSHOT"
    const val ACTION_STATUS = "com.example.capture.STATUS"

    const val BROADCAST_AUTHORIZATION_RESULT = "com.example.capture.AUTHORIZATION_RESULT"
    const val BROADCAST_SCREENSHOT_RESULT = "com.example.capture.SCREENSHOT_RESULT"
    const val BROADCAST_STATUS_RESULT = "com.example.capture.STATUS_RESULT"

    const val VIDEO_WIDTH = 720
    const val VIDEO_HEIGHT = 1280
    const val VIDEO_BIT_RATE = 4000000
    const val VIDEO_FRAME_RATE = 30

    const val MODE_REAUTH = "reauth"
    const val MODE_REUSE = "reuse"
}

data class RecordingState(
    val isRecording: Boolean = false,
    val recordingTime: String = "00:00",
    val recordingStartTime: Long = 0,
    val isTakingScreenshot: Boolean = false
)