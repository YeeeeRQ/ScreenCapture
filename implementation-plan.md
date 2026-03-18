# 双应用 Intent 通信架构实施计划

## 概述

本文档描述如何实现两个 Android 应用之间的 Intent 通信，使应用 B（AutoController）能够控制应用 A（ScreenCapture）的录屏和截屏功能。

## 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                        用户设备                              │
│  ┌─────────────────────┐    ┌─────────────────────┐      │
│  │     应用A           │    │     应用B           │      │
│  │   ScreenCapture    │◄───│  AutoController    │      │
│  │  - 录屏/截屏       │ Intent│  - Intent接收/发送 │      │
│  │  - 状态查询响应    │ Broadcast│  - 后台服务    │      │
│  │  - 广播状态        │    └─────────────────────┘      │
│  └─────────────────────┘                                  │
└─────────────────────────────────────────────────────────────┘
```

## 应用A：ScreenCapture 修改

### 1. 新增 IntentActions 常量类

**文件**: `app/src/main/java/com/example/capture/IntentActions.kt`

```kotlin
package com.example.capture

object IntentActions {
    // 控制类（外部发送）
    const val ACTION_START_RECORDING = "com.example.capture.action.START_RECORDING"
    const val ACTION_STOP_RECORDING = "com.example.capture.action.STOP_RECORDING"
    const val ACTION_TAKE_SCREENSHOT = "com.example.capture.action.TAKE_SCREENSHOT"
    const val ACTION_GET_STATUS = "com.example.capture.action.GET_STATUS"
    
    // 状态广播（返回给调用方）
    const val BROADCAST_STATUS = "com.example.capture.broadcast.STATUS"
    
    // 广播 Extra 键名
    const val EXTRA_IS_RECORDING = "is_recording"
    const val EXTRA_RECORDING_TIME = "recording_time"
}
```

### 2. 创建 CommandReceiver

**文件**: `app/src/main/java/com/example/capture/CommandReceiver.kt`

```kotlin
package com.example.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CommandReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        val service = ScreenCaptureService.getInstance()
        
        when (intent.action) {
            IntentActions.ACTION_START_RECORDING -> {
                service.startRecordingFromExternal()
            }
            IntentActions.ACTION_STOP_RECORDING -> {
                service.stopRecordingFromExternal()
            }
            IntentActions.ACTION_TAKE_SCREENSHOT -> {
                service.takeScreenshotFromExternal()
            }
            IntentActions.ACTION_GET_STATUS -> {
                sendStatusBroadcast(context, service)
            }
        }
    }
    
    private fun sendStatusBroadcast(context: Context, service: ScreenCaptureService) {
        val statusIntent = Intent(IntentActions.BROADCAST_STATUS).apply {
            putExtra(IntentActions.EXTRA_IS_RECORDING, service.isRecording())
            putExtra(IntentActions.EXTRA_RECORDING_TIME, service.getRecordingTime())
        }
        context.sendBroadcast(statusIntent)
    }
}
```

### 3. 修改 ScreenCaptureService

#### 3.1 添加单例和状态方法

```kotlin
// 单例实例
private var instance: ScreenCaptureService? = null

fun getInstance(): ScreenCaptureService {
    return instance ?: throw IllegalStateException("Service not initialized")
}

override fun onCreate() {
    super.onCreate()
    instance = this
}

override fun onDestroy() {
    instance = null
    super.onDestroy()
}

// 获取录屏时长（毫秒）
fun getRecordingTime(): Long {
    if (!isRecording) return 0
    return System.currentTimeMillis() - recordingStartTime
}

// 外部调用启动录屏（不需要权限检查）
fun startRecordingFromExternal() {
    if (isRecording) return
    // 复用现有启动逻辑
    toggleRecording()
}

// 外部调用停止录屏
fun stopRecordingFromExternal() {
    if (!isRecording) return
    toggleRecording()
}

// 外部调用截屏
fun takeScreenshotFromExternal() {
    if (!isRecording) return
    takeScreenshot()
}
```

### 4. 修改 AndroidManifest

**文件**: `app/src/main/AndroidManifest.xml`

```xml
<!-- 注册 CommandReceiver -->
<receiver 
    android:name=".CommandReceiver"
    android:exported="true"
    android:priority="1000">
    <intent-filter>
        <action android:name="com.example.capture.action.START_RECORDING" />
        <action android:name="com.example.capture.action.STOP_RECORDING" />
        <action android:name="com.example.capture.action.TAKE_SCREENSHOT" />
        <action android:name="com.example.capture.action.GET_STATUS" />
    </intent-filter>
</receiver>
```

---

## 应用B：AutoController 项目

### 1. 项目结构

```
AutoController/
├── app/
│   └── src/main/
│       ├── java/com/example/autocontroller/
│       │   ├── AutoControllerService.kt  # 后台服务
│       │   ├── StatusReceiver.kt        # 状态接收器
│       │   └── IntentActions.kt         # Intent 常量
│       └── AndroidManifest.xml
└── build.gradle
```

### 2. IntentActions（应用B侧）

**文件**: `app/src/main/java/com/example/autocontroller/IntentActions.kt`

```kotlin
package com.example.autocontroller

object ControllerActions {
    // 控制应用A
    val ACTION_START_RECORDING = "com.example.capture.action.START_RECORDING"
    val ACTION_STOP_RECORDING = "com.example.capture.action.STOP_RECORDING"
    val ACTION_TAKE_SCREENSHOT = "com.example.capture.action.TAKE_SCREENSHOT"
    val ACTION_GET_STATUS = "com.example.capture.action.GET_STATUS"
    
    // 接收应用A状态广播
    val BROADCAST_STATUS = "com.example.capture.broadcast.STATUS"
    
    // 应用B接收外部调用（供 AutoJS 发送）
    val ACTION_START_SCRIPT = "com.example.autocontroller.action.START_SCRIPT"
    val ACTION_STOP_SCRIPT = "com.example.autocontroller.action.STOP_SCRIPT"
    
    // Extra 键名
    val EXTRA_IS_RECORDING = "is_recording"
    val EXTRA_RECORDING_TIME = "recording_time"
    val EXTRA_SCRIPT_NAME = "script_name"
}
```

### 3. StatusReceiver

**文件**: `app/src/main/java/com/example/autocontroller/StatusReceiver.kt`

```kotlin
package com.example.autocontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class StatusReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "StatusReceiver"
        var lastIsRecording: Boolean = false
        var lastRecordingTime: Long = 0
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ControllerActions.BROADCAST_STATUS) {
            lastIsRecording = intent.getBooleanExtra(ControllerActions.EXTRA_IS_RECORDING, false)
            lastRecordingTime = intent.getLongExtra(ControllerActions.EXTRA_RECORDING_TIME, 0)
            
            Log.d(TAG, "Status: recording=$lastIsRecording, time=$lastRecordingTime")
        }
    }
}
```

### 4. AutoControllerService

**文件**: `app/src/main/java/com/example/autocontroller/AutoControllerService.kt`

```kotlin
package com.example.autocontroller

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log

class AutoControllerService : Service() {
    
    companion object {
        private const val TAG = "AutoController"
        
        fun startService(context: Context) {
            val intent = Intent(context, AutoControllerService::class.java)
            context.startForegroundService(intent)
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, AutoControllerService::class.java)
            context.stopService(intent)
        }
    }
    
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ControllerActions.ACTION_START_SCRIPT -> {
                    val scriptName = intent.getStringExtra(ControllerActions.EXTRA_SCRIPT_NAME)
                    startRecording()
                    Log.d(TAG, "Started recording for script: $scriptName")
                }
                
                ControllerActions.ACTION_STOP_SCRIPT -> {
                    stopRecording()
                    Log.d(TAG, "Stopped recording")
                }
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        registerCommandReceiver()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        unregisterReceiver(commandReceiver)
        super.onDestroy()
    }
    
    private fun registerCommandReceiver() {
        val filter = IntentFilter().apply {
            addAction(ControllerActions.ACTION_START_SCRIPT)
            addAction(ControllerActions.ACTION_STOP_SCRIPT)
        }
        registerReceiver(commandReceiver, filter)
    }
    
    // 控制应用A
    fun startRecording() {
        val intent = Intent(ControllerActions.ACTION_START_RECORDING)
        sendBroadcast(intent)
    }
    
    fun stopRecording() {
        val intent = Intent(ControllerActions.ACTION_STOP_RECORDING)
        sendBroadcast(intent)
    }
    
    fun takeScreenshot() {
        val intent = Intent(ControllerActions.ACTION_TAKE_SCREENSHOT)
        sendBroadcast(intent)
    }
    
    fun queryStatus() {
        val intent = Intent(ControllerActions.ACTION_GET_STATUS)
        sendBroadcast(intent)
    }
}
```

### 5. AndroidManifest

**文件**: `app/src/main/AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 权限声明 -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    
    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="AutoController"
        android:theme="@android:style/Theme.Material.Light">
        
        <!-- 后台服务 -->
        <service 
            android:name=".AutoControllerService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
        
        <!-- 状态接收器 -->
        <receiver 
            android:name=".StatusReceiver"
            android:enabled="true"
            android:exported="false">
            <intent-filter>
                <action android:name="com.example.capture.broadcast.STATUS" />
            </intent-filter>
        </receiver>
        
    </application>
</manifest>
```

---

## AutoJS 调用示例

### 1. 控制应用A（直接调用）

```javascript
// 启动录屏
var intent1 = new android.content.Intent("com.example.capture.action.START_RECORDING");
context.sendBroadcast(intent1);

// 停止录屏
var intent2 = new android.content.Intent("com.example.capture.action.STOP_RECORDING");
context.sendBroadcast(intent2);

// 截屏
var intent3 = new android.content.Intent("com.example.capture.action.TAKE_SCREENSHOT");
context.sendBroadcast(intent3);

// 查询状态
var intent4 = new android.content.Intent("com.example.capture.action.GET_STATUS");
context.sendBroadcast(intent4);
```

### 2. 通过应用B控制

```javascript
// 通过应用B启动脚本（录屏+自动化）
var intent = new android.content.Intent("com.example.autocontroller.action.START_SCRIPT");
intent.putExtra("script_name", "my_automation.js");
context.sendBroadcast(intent);

// 通过应用B停止
var intent2 = new android.content.Intent("com.example.autocontroller.action.STOP_SCRIPT");
context.sendBroadcast(intent2);
```

### 3. 接收状态广播

```javascript
var receiver = android.content.BroadcastReceiver({
    onReceive: function(context, intent) {
        var isRecording = intent.getBooleanExtra("is_recording", false);
        var time = intent.getLongExtra("recording_time", 0);
        
        toast("录屏状态: " + isRecording);
        toast("录屏时长: " + (time / 1000) + "秒");
    }
});

var filter = new android.content.IntentFilter("com.example.capture.broadcast.STATUS");
context.registerReceiver(receiver, filter);

// 查询状态后等待广播
context.sendBroadcast(new android.content.Intent("com.example.capture.action.GET_STATUS"));
```

---

## 实施步骤

### 步骤1：修改应用A

1. 创建 `IntentActions.kt` 常量类
2. 创建 `CommandReceiver.kt` 广播接收器
3. 修改 `ScreenCaptureService.kt`：
   - 添加 `getInstance()` 单例方法
   - 添加 `getRecordingTime()` 方法
   - 添加外部调用方法
4. 修改 `AndroidManifest.xml` 注册 Receiver
5. 编译测试

### 步骤2：创建应用B

1. 创建新 Android 项目 AutoController
2. 添加 `IntentActions.kt`
3. 创建 `StatusReceiver.kt`
4. 创建 `AutoControllerService.kt`
5. 配置 `AndroidManifest.xml`
6. 编译安装

### 步骤3：测试

1. 测试应用A独立功能（录屏/截屏）
2. 测试 AutoJS 直接调用应用A
3. 测试应用B控制应用A
4. 测试状态查询功能

---

## 文件变更清单

### 应用A变更

| 文件 | 操作 |
|------|------|
| `app/src/main/java/com/example/capture/IntentActions.kt` | 新增 |
| `app/src/main/java/com/example/capture/CommandReceiver.kt` | 新增 |
| `app/src/main/java/com/example/capture/ScreenRecordService.kt` | 修改 |
| `app/src/main/AndroidManifest.xml` | 修改 |

### 应用B新增

| 文件 |
|------|
| `AutoController/build.gradle` |
| `app/src/main/AndroidManifest.xml` |
| `app/src/main/java/com/example/autocontroller/IntentActions.kt` |
| `app/src/main/java/com/example/autocontroller/StatusReceiver.kt` |
| `app/src/main/java/com/example/autocontroller/AutoControllerService.kt` |

---

## 待确认事项

- [ ] 是否需要权限保护（同签名/自定义权限）
- [ ] 是否需要返回截图/录屏文件路径
- [ ] 应用B是否需要 UI 界面
