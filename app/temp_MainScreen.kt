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
    var hasPermissions by remember { mutableStateOf(PermissionHelper.hasAllPermissions(context)) }
    var showModeDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        hasPermissions = PermissionHelper.hasAllPermissions(context)
    }
    
    // Mode selection dialog
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
                Text(text = recordingTime, fontSize = 48.sp, color = Color.Red)
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = if (isRecording) "录制中" else "屏幕录制", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = if (isRecording) "点击按钮停止" else "点击按钮开始", color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("悬浮窗")
                Spacer(modifier = Modifier.width(8.dp))
                Switch(checked = floatingWindowEnabled, onCheckedChange = onFloatingWindowToggle)
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            if (!hasPermissions) {
                OutlinedButton(onClick = { PermissionHelper.requestPermissions(context as Activity) }) {
                    Text("获取权限")
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
                        Icon(painterResource(if (isRecording) R.drawable.ic_stop else R.drawable.ic_record), null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isRecording) "停止录制" else "开始录制", color = Color.White)
                    }
                }
            }
        }
    }
}
