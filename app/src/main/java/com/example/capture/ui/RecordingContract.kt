package com.example.capture.ui

import com.example.capture.helper.SettingsManager

data class RecordingUiState(
    val isRecording: Boolean = false,
    val recordingTime: String = "00:00",
    val floatingWindowEnabled: Boolean = false,
    val recordingMode: String = SettingsManager.MODE_REAUTH,
    val themeMode: String = SettingsManager.THEME_SYSTEM,
    val hasPermissions: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isTakingScreenshot: Boolean = false
)

sealed class RecordingIntent {
    data object StartRecording : RecordingIntent()
    data object StopRecording : RecordingIntent()
    data class ToggleFloatingWindow(val enabled: Boolean) : RecordingIntent()
    data class SetRecordingMode(val mode: String) : RecordingIntent()
    data class SetThemeMode(val mode: String) : RecordingIntent()
    data object RequestOverlayPermission : RecordingIntent()
    data object RequestNotificationPermission : RecordingIntent()
    data object RequestStoragePermission : RecordingIntent()
    data object RefreshPermissions : RecordingIntent()
}

sealed class RecordingUiEvent {
    data object RequestMediaProjection : RecordingUiEvent()
    data object RequestOverlayPermission : RecordingUiEvent()
    data object RequestNotificationPermission : RecordingUiEvent()
    data object RequestStoragePermission : RecordingUiEvent()
    data class ShowToast(val message: String) : RecordingUiEvent()
    data object NavigateBack : RecordingUiEvent()
    data class StartRecordingService(val resultCode: Int, val data: android.content.Intent) : RecordingUiEvent()
    data object StopRecordingService : RecordingUiEvent()
}