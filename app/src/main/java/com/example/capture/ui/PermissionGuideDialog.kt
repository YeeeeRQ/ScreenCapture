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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class PermissionItemData(
    val title: String,
    val description: String,
    val isGranted: Boolean
)

@Composable
fun PermissionGuideDialog(
    hasOverlay: Boolean,
    hasNotification: Boolean,
    hasStorage: Boolean,
    onStartGuide: () -> Unit,
    onDismiss: () -> Unit
) {
    val items = listOf(
        PermissionItemData("悬浮窗权限", "用于显示录制控制按钮", hasOverlay),
        PermissionItemData("通知权限", "用于接收录制完成提示", hasNotification),
        PermissionItemData("存储权限", "用于保存录制的视频文件", hasStorage)
    )

    val grantedCount = items.count { it.isGranted }
    val totalCount = items.size
    val allGranted = grantedCount == totalCount

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "权限获取 ($grantedCount/$totalCount)",
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
                    text = if (allGranted) "所有权限已获取！" else "请依次获取以下权限：",
                    fontSize = 14.sp,
                    color = if (allGranted) Color(0xFF4CAF50) else Color.Gray
                )

                Spacer(modifier = Modifier.height(4.dp))

                items.forEach { item ->
                    PermissionItem(item)
                }
            }
        },
        confirmButton = {
            if (allGranted) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Text("完成")
                }
            } else {
                Button(
                    onClick = {
                        onDismiss()
                        onStartGuide()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF7B7BDB)
                    )
                ) {
                    Text("开始引导")
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun PermissionItem(item: PermissionItemData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (item.isGranted) Color(0xFFE8F5E9) else Color(0xFFF5F5F5),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (item.isGranted) "✓" else "○",
            fontSize = 20.sp,
            color = if (item.isGranted) Color(0xFF4CAF50) else Color.Gray,
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = if (item.isGranted) Color(0xFF4CAF50).copy(alpha = 0.1f) else Color.Gray.copy(alpha = 0.1f),
                    shape = CircleShape
                )
                .padding(8.dp),
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Text(
                text = item.title,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = if (item.isGranted) Color(0xFF4CAF50) else Color.Black
            )
            Text(
                text = item.description,
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}