package com.example.capture.ui

import android.app.Activity
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.capture.R
import com.example.capture.helper.PermissionHelper
import com.example.capture.helper.SettingsManager

@Composable
fun MainScreen(
    uiState: RecordingUiState,
    onIntent: (RecordingIntent) -> Unit
) {
    val context = LocalContext.current
    var showModeDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }

    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text("录制模式") },
            text = {
                Column {
                    Text("当前: ${if (uiState.recordingMode == SettingsManager.MODE_REAUTH) "每次重新授权" else "权限复用"}")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            onIntent(RecordingIntent.SetRecordingMode(SettingsManager.MODE_REAUTH))
                            showModeDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("每次重新授权")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            onIntent(RecordingIntent.SetRecordingMode(SettingsManager.MODE_REUSE))
                            showModeDialog = false
                        },
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

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("主题模式") },
            text = {
                Column {
                    Text("当前: ${when (uiState.themeMode) {
                        SettingsManager.THEME_LIGHT -> "浅色"
                        SettingsManager.THEME_DARK -> "深色"
                        else -> "跟随系统"
                    }}")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            onIntent(RecordingIntent.SetThemeMode(SettingsManager.THEME_LIGHT))
                            showThemeDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("浅色")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            onIntent(RecordingIntent.SetThemeMode(SettingsManager.THEME_DARK))
                            showThemeDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("深色")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            onIntent(RecordingIntent.SetThemeMode(SettingsManager.THEME_SYSTEM))
                            showThemeDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("跟随系统")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    val isDarkTheme = when (uiState.themeMode) {
        SettingsManager.THEME_LIGHT -> false
        SettingsManager.THEME_DARK -> true
        else -> false
    }

    val backgroundColor = if (isDarkTheme) Color(0xFF1E1E2E) else Color(0xFFF5F5F5)
    val cardColor = if (isDarkTheme) Color(0xFF2D2D44) else Color(0xFFFFFFFF)
    val textColor = if (isDarkTheme) Color.White else Color(0xFF1E1E2E)
    val textSecondaryColor = if (isDarkTheme) Color.White.copy(alpha = 0.7f) else Color.Gray

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        color = if (uiState.isRecording) Color.Gray else Color(0xFFE94560),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        id = if (uiState.isRecording) R.drawable.ic_stop else R.drawable.ic_record
                    ),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.isRecording) {
                Text(
                    text = uiState.recordingTime,
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 48.sp),
                    color = Color(0xFFE94560)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (uiState.isRecording) "录制中" else "屏幕录制",
                style = MaterialTheme.typography.titleLarge,
                color = textColor
            )

            Spacer(modifier = Modifier.weight(1f))

            if (!uiState.hasPermissions) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = { onIntent(RecordingIntent.RequestOverlayPermission) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D3D5C))
                    ) {
                        Text("获取悬浮窗权限")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onIntent(RecordingIntent.RequestNotificationPermission) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D3D5C))
                    ) {
                        Text("获取通知权限")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onIntent(RecordingIntent.RequestStoragePermission) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3D3D5C))
                    ) {
                        Text("获取存储权限")
                    }
                }
            } else {
                Button(
                    onClick = {
                        if (uiState.isRecording) {
                            onIntent(RecordingIntent.StopRecording)
                        } else {
                            onIntent(RecordingIntent.StartRecording)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isRecording) Color.Gray else Color(0xFFE94560)
                    )
                ) {
                    Icon(
                        painter = painterResource(if (uiState.isRecording) R.drawable.ic_stop else R.drawable.ic_record),
                        contentDescription = null,
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (uiState.isRecording) "停止录制" else "开始录制",
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("悬浮窗", color = textColor)
                        Switch(
                            checked = uiState.floatingWindowEnabled,
                            onCheckedChange = { enabled ->
                                onIntent(RecordingIntent.ToggleFloatingWindow(enabled))
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "录制模式: ${if (uiState.recordingMode == SettingsManager.MODE_REAUTH) "每次授权" else "权限复用"}",
                            color = textSecondaryColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { showModeDialog = true }) {
                            Text("更改", color = Color(0xFF7B7BDB))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "主题: ${when (uiState.themeMode) {
                                SettingsManager.THEME_LIGHT -> "浅色"
                                SettingsManager.THEME_DARK -> "深色"
                                else -> "跟随系统"
                            }}",
                            color = textSecondaryColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { showThemeDialog = true }) {
                            Text("更改", color = Color(0xFF7B7BDB))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}