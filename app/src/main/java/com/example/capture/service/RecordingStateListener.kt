package com.example.capture.service

interface RecordingStateListener {
    fun onRecordingStateChanged(state: RecordingState)
}