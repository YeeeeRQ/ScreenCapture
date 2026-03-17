package com.example.capture

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.capture.helper.PermissionHelper
import com.example.capture.helper.SettingsManager
import com.example.capture.service.ScreenRecordService
import com.example.capture.ui.theme.CaptureTheme
import com.example.capture.view.FloatingView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    private var screenRecordService: ScreenRecordService? = null
    private var serviceBound = false
    private var floatingView: FloatingView? = null
    private var localRecordingStartTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private var requestedFromFloatingWindow = false
    
    var isRecording by mutableStateOf(false)
        private set
    var recordingTime by mutableStateOf("00:00")
        private set
    var floatingWindowEnabled by mutableStateOf(false)
        private set
    var recordingMode by mutableStateOf(SettingsManager.MODE_REAUTH)
        private set
    
    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            val service = screenRecordService
            val startTime = if (service != null && service.isRecording()) {
                service.getRecordingStartTime().takeIf { it > 0 } ?: localRecordingStartTime
            } else {
                localRecordingStartTime
            }
            
            if (startTime > 0) {
                val elapsed = System.currentTimeMillis() - startTime
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / 1000) / 60
                val timeText = String.format("%02d:%02d", minutes, seconds)
                recordingTime = timeText
                floatingView?.updateRecordingTime(timeText)
            }
            handler.postDelayed(this, 1000)
        }
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d("ScreenRecord", "onServiceConnected called!")
            val binder = service as ScreenRecordService.LocalBinder
            screenRecordService = binder.getService()
            serviceBound = true
            isRecording = screenRecordService?.isRecording() == true
            
            screenRecordService?.setActivity(this@MainActivity)
            
            pendingProjectionData?.let { (resultCode, data) ->
                Log.d("ScreenRecord", "Passing pending projection data to service")
                screenRecordService?.saveProjectionData(resultCode, data)
            }
            
            if (floatingWindowEnabled) {
                floatingView = FloatingView.getInstance(this@MainActivity)
                floatingView?.setService(screenRecordService)
                floatingView?.isRecording = isRecording
                floatingView?.show()
                
                // 将 FloatingView 传递给 Service
                screenRecordService?.setFloatingView(floatingView)
            }
            
            handler.post(updateTimeRunnable)
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("ScreenRecord", "onServiceDisconnected called!")
            screenRecordService?.clearActivity()
            screenRecordService = null
            serviceBound = false
            floatingView?.setService(null)
        }
    }

    private var requestPermissionOnly = false
    private var pendingProjectionData: Pair<Int, Intent>? = null
    
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("ScreenRecord", "mediaProjectionLauncher callback, resultCode=${result.resultCode}")
        
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            pendingProjectionData = Pair(result.resultCode, result.data!!)
            Log.d("ScreenRecord", "Permission data cached: resultCode=${result.resultCode}")
            
            screenRecordService?.saveProjectionData(result.resultCode, result.data!!)
            
            if (requestPermissionOnly) {
                Log.d("ScreenRecord", "Permission only mode, not starting recording")
                Toast.makeText(this, "权限已获取，可以使用悬浮窗录制", Toast.LENGTH_SHORT).show()
            } else {
                Log.d("ScreenRecord", "Starting recording after permission granted")
                startRecordingService(result.resultCode, result.data!!)
                
                // If started from floating window, hide activity
                if (requestedFromFloatingWindow) {
                    Log.d("ScreenRecord", "Finishing activity after recording started")
                    Handler(Looper.getMainLooper()).postDelayed({
                        moveTaskToBack(true)
                    }, 500)
                }
            }
            requestPermissionOnly = false
        } else {
            Log.d("ScreenRecord", "Permission denied or cancelled")
            Toast.makeText(this, "需要屏幕捕获权限才能录制", Toast.LENGTH_SHORT).show()
            requestPermissionOnly = false
        }
    }
    
    private val permissionRequestReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            Log.d("ScreenRecord", "Received permission request from floating window")
            requestMediaProjection(permissionOnly = true)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // 使状态栏和导航栏透明
        window.setFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
        )
        window.setFlags(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
        )
        
        registerReceiver(permissionRequestReceiver, android.content.IntentFilter("com.example.capture.REQUEST_PERMISSION"), android.content.Context.RECEIVER_NOT_EXPORTED)
        
        Log.i("ScreenRecord", "========== App Started ==========")
        
        recordingMode = SettingsManager.getRecordingMode(this)
        Log.d("ScreenRecord", "Recording mode: $recordingMode")
        
        requestedFromFloatingWindow = getSharedPreferences("screen_record_prefs", MODE_PRIVATE)
            .getBoolean("from_floating_window", false)
        
        getSharedPreferences("screen_record_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("from_floating_window", false)
            .apply()
        
        Log.d("ScreenRecord", "Started from floating window: $requestedFromFloatingWindow")
        
        if (requestedFromFloatingWindow) {
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d("ScreenRecord", "Requesting MediaProjection permission (from floating window)...")
                requestMediaProjection(permissionOnly = false)
            }, 300)
        }
        // Auto-request permission removed - user can manually request from UI
        
        setContent {
            CaptureTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        isRecording = isRecording,
                        recordingTime = recordingTime,
                        onStartRecording = { startScreenCapture() },
                        onStopRecording = { stopRecording() },
                        floatingWindowEnabled = floatingWindowEnabled,
                        onFloatingWindowToggle = { enabled ->
                            floatingWindowEnabled = enabled
                            if (enabled) {
                                floatingView = FloatingView.getInstance(this)
                                floatingView?.setService(screenRecordService)
                                floatingView?.isRecording = isRecording
                                floatingView?.show()
                            } else {
                                floatingView?.hide()
                            }
                        },
                        recordingMode = recordingMode,
                        onRecordingModeChange = { mode ->
                            recordingMode = mode
                            SettingsManager.setRecordingMode(this, mode)
                            screenRecordService?.setRecordingMode(mode)
                        }
                    )
                }
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        Log.d("ScreenRecord", "onStart called, binding service...")
        Intent(this, ScreenRecordService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateTimeRunnable)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        floatingView?.hide()
        floatingView = null
    }
    
    private fun stopRecording() {
        handler.removeCallbacks(updateTimeRunnable)
        
        isRecording = false
        recordingTime = "00:00"
        localRecordingStartTime = 0
        
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        startService(intent)
        
        floatingView?.isRecording = false
        
        Toast.makeText(this, "视频已保存到相册", Toast.LENGTH_LONG).show()
    }
    
    private fun startScreenCapture() {
        Log.d("ScreenRecord", "=== startScreenCapture called ===")
        if (!PermissionHelper.hasOverlayPermission(this)) {
            Log.d("ScreenRecord", "No overlay permission, requesting...")
            PermissionHelper.requestOverlayPermission(this)
            return
        }
        
        val projectionData = PermissionHelper.getMediaProjectionData(this)
        Log.d("ScreenRecord", "projectionData: ${projectionData != null}")
        if (projectionData != null) {
            startRecordingService(projectionData.first, projectionData.second)
        } else {
            Log.d("ScreenRecord", "No projection data, requesting media projection...")
            requestMediaProjection()
        }
    }
    
    private fun requestMediaProjection(permissionOnly: Boolean = false) {
        requestPermissionOnly = permissionOnly
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }
    
    private fun startRecordingService(resultCode: Int, data: Intent) {
        Log.d("ScreenRecord", "=== startRecordingService called ===")
        isRecording = true
        localRecordingStartTime = System.currentTimeMillis()
        
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra("result_code", resultCode)
            putExtra("data", data)
        }
        Log.d("ScreenRecord", "Starting foreground service...")
        startForegroundService(intent)
        Log.d("ScreenRecord", "Foreground service started")
        
        screenRecordService?.saveProjectionData(resultCode, data)
        
        if (floatingWindowEnabled) {
            floatingView = FloatingView.getInstance(this).apply {
                setService(screenRecordService)
            }
            floatingView?.isRecording = true
            floatingView?.show()
        }
        handler.post(updateTimeRunnable)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1003) {
            if (PermissionHelper.hasOverlayPermission(this)) {
                startScreenCapture()
            } else {
                Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == 1001) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d("ScreenRecord", "MediaProjection permission granted via onActivityResult")
                screenRecordService?.saveProjectionData(resultCode, data)
                
                // Use moveTaskToBack instead of finish to keep activity alive
                Log.d("ScreenRecord", "Moving task to back after permission")
                window.decorView.postDelayed({
                    moveTaskToBack(true)
                }, 500)
            }
        }
    }
}

@Composable
fun MainScreen(
    isRecording: Boolean,
    recordingTime: String,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    floatingWindowEnabled: Boolean = false,
    onFloatingWindowToggle: (Boolean) -> Unit = {},
    recordingMode: String = SettingsManager.MODE_REAUTH,
    onRecordingModeChange: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasPermissions by remember { mutableStateOf(PermissionHelper.hasAllPermissions(context)) }
    var showModeDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        hasPermissions = PermissionHelper.hasAllPermissions(context)
    }
    
    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text("录制模式") },
            text = {
                Column {
                    Text("当前: ${if (recordingMode == SettingsManager.MODE_REAUTH) "每次重新授权" else "权限复用"}")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { onRecordingModeChange(SettingsManager.MODE_REAUTH); showModeDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("每次重新授权")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onRecordingModeChange(SettingsManager.MODE_REUSE); showModeDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("权限复用")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModeDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
    
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = { showModeDialog = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "设置")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(
                    id = if (isRecording) R.drawable.ic_stop else R.drawable.ic_record
                ),
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = if (isRecording) Color.Red else Color.Gray
            )
            
            if (isRecording) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = recordingTime,
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 48.sp),
                    color = Color.Red
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = if (isRecording) "录制中" else "屏幕录制",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isRecording) "点击按钮停止" else "点击按钮开始",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("悬浮窗")
                Spacer(modifier = Modifier.width(8.dp))
                Switch(checked = floatingWindowEnabled, onCheckedChange = onFloatingWindowToggle)
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            if (!hasPermissions) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    OutlinedButton(onClick = { 
                        val activity = context as Activity
                        PermissionHelper.requestOverlayPermission(activity)
                    }) {
                        Text("获取悬浮窗权限")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { PermissionHelper.requestPermissions(context as Activity) }) {
                        Text("获取通知权限")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { PermissionHelper.requestManageStoragePermission(context as Activity) }) {
                        Text("获取存储权限")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "需要：悬浮窗、通知、存储权限才能使用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(if (isRecording) Color.Gray else Color.Red)
                        .clickable {
                            val activity = context as Activity
                            if (PermissionHelper.hasAllPermissions(activity)) {
                                if (isRecording) onStopRecording() else onStartRecording()
                            } else {
                                PermissionHelper.requestPermissions(activity)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(if (isRecording) R.drawable.ic_stop else R.drawable.ic_record),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isRecording) "停止录制" else "开始录制", color = Color.White)
                    }
                }
            }
        }
    }
}
