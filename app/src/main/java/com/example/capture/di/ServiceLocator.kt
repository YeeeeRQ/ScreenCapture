package com.example.capture.di

import android.content.Context
import com.example.capture.helper.PermissionHelper
import com.example.capture.helper.SettingsManager
import com.example.capture.service.RecordingServiceInterface
import com.example.capture.service.ScreenRecordService

object ServiceLocator {
    private var recordingService: RecordingServiceInterface? = null

    fun provideRecordingService(): RecordingServiceInterface? = recordingService

    fun registerRecordingService(service: RecordingServiceInterface) {
        recordingService = service
    }

    fun unregisterRecordingService() {
        recordingService = null
    }

    fun provideSettingsManager(): SettingsManager {
        return SettingsManager
    }

    fun providePermissionHelper(): PermissionHelper {
        return PermissionHelper
    }
}