package com.example.capture.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.capture.helper.PermissionHelper
import com.example.capture.helper.SettingsManager
import com.example.capture.service.RecordingServiceInterface
import com.example.capture.service.ScreenRecordService
import com.example.capture.view.FloatingView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RecordingViewModel(
    private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "RecordingViewModel"
    }

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<RecordingUiEvent>()
    val uiEvent: SharedFlow<RecordingUiEvent> = _uiEvent.asSharedFlow()

    private var screenRecordService: RecordingServiceInterface? = null
    private var serviceBound = false
    private var floatingView: FloatingView? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "onServiceConnected called!")
            val binder = service as ScreenRecordService.LocalBinder
            screenRecordService = binder.getService()
            serviceBound = true

            observeRecordingState()

            pendingProjectionData?.let { (resultCode, data) ->
                Log.d(TAG, "Passing pending projection data to service")
                screenRecordService?.saveProjectionData(resultCode, data)
            }

            if (_uiState.value.floatingWindowEnabled) {
                floatingView = FloatingView.getInstance(context)
                floatingView?.setService(screenRecordService)
                floatingView?.show()
                screenRecordService?.setFloatingView(floatingView)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "onServiceDisconnected called!")
            screenRecordService = null
            serviceBound = false
            floatingView?.setService(null)
        }
    }

    private var pendingProjectionData: Pair<Int, Intent>? = null

    init {
        _uiState.update { state ->
            state.copy(
                floatingWindowEnabled = SettingsManager.getFloatingWindowEnabled(context),
                recordingMode = SettingsManager.getRecordingMode(context),
                themeMode = SettingsManager.getThemeMode(context),
                hasPermissions = PermissionHelper.hasAllPermissions(context)
            )
        }
    }

    fun bindService(activity: Activity) {
        Intent(activity, ScreenRecordService::class.java).also { intent ->
            activity.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun unbindService(activity: Activity) {
        if (serviceBound) {
            activity.unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun observeRecordingState() {
        viewModelScope.launch {
            screenRecordService?.recordingState?.collect { state ->
                _uiState.update { it.copy(isRecording = state.isRecording, recordingTime = state.recordingTime, isTakingScreenshot = state.isTakingScreenshot) }
                floatingView?.updateRecordingState(state)
            }
        }
    }

    fun processIntent(intent: RecordingIntent) {
        viewModelScope.launch {
            when (intent) {
                is RecordingIntent.StartRecording -> handleStartRecording()
                is RecordingIntent.StopRecording -> handleStopRecording()
                is RecordingIntent.ToggleFloatingWindow -> handleToggleFloatingWindow(intent.enabled)
                is RecordingIntent.SetRecordingMode -> handleSetRecordingMode(intent.mode)
                is RecordingIntent.SetThemeMode -> handleSetThemeMode(intent.mode)
                is RecordingIntent.RequestOverlayPermission -> handleRequestOverlayPermission()
                is RecordingIntent.RequestNotificationPermission -> handleRequestNotificationPermission()
                is RecordingIntent.RequestStoragePermission -> handleRequestStoragePermission()
                is RecordingIntent.RefreshPermissions -> handleRefreshPermissions()
            }
        }
    }

    private suspend fun handleStartRecording() {
        if (!PermissionHelper.hasOverlayPermission(context)) {
            _uiEvent.emit(RecordingUiEvent.RequestOverlayPermission)
            return
        }

        val projectionData = PermissionHelper.getMediaProjectionData(context)
        if (projectionData != null) {
            screenRecordService?.saveProjectionData(projectionData.first, projectionData.second)
            _uiEvent.emit(RecordingUiEvent.StartRecordingService(projectionData.first, projectionData.second))
        } else {
            _uiEvent.emit(RecordingUiEvent.RequestMediaProjection)
        }
    }

    private suspend fun handleStopRecording() {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        context.startService(intent)
        _uiState.update { it.copy(isRecording = false, recordingTime = "00:00") }
    }

    private fun handleToggleFloatingWindow(enabled: Boolean) {
        _uiState.update { it.copy(floatingWindowEnabled = enabled) }
        SettingsManager.setFloatingWindowEnabled(context, enabled)

        if (enabled) {
            floatingView = FloatingView.getInstance(context)
            floatingView?.setService(screenRecordService)
            floatingView?.show()
            screenRecordService?.setFloatingView(floatingView)
        } else {
            floatingView?.hide()
            floatingView = null
        }
    }

    private fun handleSetRecordingMode(mode: String) {
        _uiState.update { it.copy(recordingMode = mode) }
        SettingsManager.setRecordingMode(context, mode)
        screenRecordService?.setRecordingMode(mode)
    }

    private fun handleSetThemeMode(mode: String) {
        _uiState.update { it.copy(themeMode = mode) }
        SettingsManager.setThemeMode(context, mode)
    }

    private fun handleRequestOverlayPermission() {
        viewModelScope.launch {
            _uiEvent.emit(RecordingUiEvent.RequestOverlayPermission)
        }
    }

    private fun handleRequestNotificationPermission() {
        viewModelScope.launch {
            _uiEvent.emit(RecordingUiEvent.RequestNotificationPermission)
        }
    }

    private fun handleRequestStoragePermission() {
        viewModelScope.launch {
            _uiEvent.emit(RecordingUiEvent.RequestStoragePermission)
        }
    }

    private fun handleRefreshPermissions() {
        _uiState.update { it.copy(hasPermissions = PermissionHelper.hasAllPermissions(context)) }
    }

    fun saveProjectionData(resultCode: Int, data: Intent) {
        pendingProjectionData = Pair(resultCode, data)
        screenRecordService?.saveProjectionData(resultCode, data)
    }

    fun onMediaProjectionResult(resultCode: Int, data: Intent) {
        viewModelScope.launch {
            saveProjectionData(resultCode, data)
            _uiEvent.emit(RecordingUiEvent.StartRecordingService(resultCode, data))
        }
    }

    fun startRecordingService(resultCode: Int, data: Intent) {
        viewModelScope.launch {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                putExtra("result_code", resultCode)
                putExtra("data", data)
            }
            context.startForegroundService(intent)
        }
    }

    fun showToast(message: String) {
        viewModelScope.launch {
            _uiEvent.emit(RecordingUiEvent.ShowToast(message))
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return RecordingViewModel(context.applicationContext) as T
        }
    }
}