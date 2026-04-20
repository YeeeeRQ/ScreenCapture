package com.example.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import com.example.capture.helper.PermissionHelper
import com.example.capture.helper.SettingsManager
import com.example.capture.service.ScreenRecordService
import com.example.capture.ui.MainScreen
import com.example.capture.ui.RecordingIntent
import com.example.capture.ui.RecordingUiEvent
import com.example.capture.ui.RecordingViewModel
import com.example.capture.ui.theme.CaptureTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var requestedFromFloatingWindow = false

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("ScreenRecord", "mediaProjectionLauncher callback, resultCode=${result.resultCode}")

        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d("ScreenRecord", "Permission data cached: resultCode=${result.resultCode}")
            viewModel.onMediaProjectionResult(result.resultCode, result.data!!)

            if (requestedFromFloatingWindow) {
                Log.d("ScreenRecord", "Finishing activity after recording started")
                Handler(Looper.getMainLooper()).postDelayed({
                    moveTaskToBack(true)
                }, 500)
            }
        } else {
            Log.d("ScreenRecord", "Permission denied or cancelled")
            Toast.makeText(this, "需要屏幕捕获权限才能录制", Toast.LENGTH_SHORT).show()
        }
    }

    private lateinit var viewModel: RecordingViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(
            this,
            RecordingViewModel.Factory(applicationContext)
        )[RecordingViewModel::class.java]

        enableEdgeToEdge()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        requestedFromFloatingWindow = getSharedPreferences("screen_record_prefs", MODE_PRIVATE)
            .getBoolean("from_floating_window", false)

        getSharedPreferences("screen_record_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("from_floating_window", false)
            .apply()

        Log.i("ScreenRecord", "========== App Started ==========")
        Log.d("ScreenRecord", "Started from floating window: $requestedFromFloatingWindow")

        setContent {
            val uiState by viewModel.uiState.collectAsState()

            val isDarkTheme = when (uiState.themeMode) {
                SettingsManager.THEME_LIGHT -> false
                SettingsManager.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }

            val isDark = uiState.themeMode == SettingsManager.THEME_DARK ||
                    (uiState.themeMode == SettingsManager.THEME_SYSTEM &&
                            resources.configuration.uiMode and
                            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isDark

            CaptureTheme(darkTheme = isDarkTheme, dynamicColor = false) {
                LaunchedEffect(Unit) {
                    viewModel.processIntent(RecordingIntent.RefreshPermissions)
                }

                LaunchedEffect(Unit) {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.uiEvent.collectLatest { event ->
                            handleUiEvent(event)
                        }
                    }
                }

                MainScreen(
                    uiState = uiState,
                    onIntent = viewModel::processIntent
                )
            }
        }
    }

    private fun handleUiEvent(event: RecordingUiEvent) {
        when (event) {
            is RecordingUiEvent.RequestMediaProjection -> {
                val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                mediaProjectionLauncher.launch(intent)
            }
            is RecordingUiEvent.RequestOverlayPermission -> {
                PermissionHelper.requestOverlayPermission(this)
            }
            is RecordingUiEvent.RequestNotificationPermission -> {
                PermissionHelper.requestPermissions(this)
            }
            is RecordingUiEvent.RequestStoragePermission -> {
                PermissionHelper.requestManageStoragePermission(this)
            }
            is RecordingUiEvent.ShowToast -> {
                Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            }
            is RecordingUiEvent.NavigateBack -> {
                moveTaskToBack(true)
            }
            is RecordingUiEvent.StartRecordingService -> {
                viewModel.startRecordingService(event.resultCode, event.data)
            }
            is RecordingUiEvent.StopRecordingService -> {
                val intent = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_STOP
                }
                startService(intent)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d("ScreenRecord", "onStart called, binding service...")
        viewModel.bindService(this)

        if (requestedFromFloatingWindow) {
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d("ScreenRecord", "Requesting MediaProjection permission (from floating window)...")
                viewModel.processIntent(RecordingIntent.StartRecording)
            }, 300)
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.unbindService(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1003) {
            if (PermissionHelper.hasOverlayPermission(this)) {
                viewModel.processIntent(RecordingIntent.StartRecording)
            } else {
                Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == 1001) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d("ScreenRecord", "MediaProjection permission granted via onActivityResult")
                viewModel.onMediaProjectionResult(resultCode, data)
                window.decorView.postDelayed({
                    moveTaskToBack(true)
                }, 500)
            }
        }
    }
}