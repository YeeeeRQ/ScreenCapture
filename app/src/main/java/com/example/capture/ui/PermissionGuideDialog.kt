package com.example.capture.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PermissionGuideDialog(
    onRequestAllPermissions: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "需要以下权限",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "请授予以下权限以确保应用正常运行：",
                    fontSize = 14.sp,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(4.dp))

                PermissionItem(
                    emoji = "🎯",
                    title = "悬浮窗权限",
                    description = "用于显示录制控制按钮"
                )

                PermissionItem(
                    emoji = "🔔",
                    title = "通知权限",
                    description = "用于接收录制完成提示"
                )

                PermissionItem(
                    emoji = "📁",
                    title = "存储权限",
                    description = "用于保存录制的视频文件"
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onRequestAllPermissions()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF7B7BDB)
                )
            ) {
                Text("一键获取所有权限")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("稍后")
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun PermissionItem(
    emoji: String,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color(0xFFF5F5F5),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = emoji,
            fontSize = 24.sp,
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = Color(0xFF7B7BDB).copy(alpha = 0.1f),
                    shape = CircleShape
                )
                .padding(8.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Text(
                text = title,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}