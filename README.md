# Screen Recorder

Android 屏幕录制应用，支持悬浮窗控制和浅色/深色主题。

## 功能特性

- 屏幕录制（无需音频）
- 悬浮窗控制
- 浅色/深色/跟随系统主题
- 录制视频保存到相册（MP4格式）
- 两种录制模式：
  - 每次重新授权（更安全）
  - 权限复用（更便捷）

## 技术栈

- Kotlin
- Jetpack Compose
- MediaProjection API
- Foreground Service

## 权限说明

应用需要以下权限：

- `SYSTEM_ALERT_WINDOW` - 悬浮窗权限
- `POST_NOTIFICATIONS` - 通知权限
- `MANAGE_EXTERNAL_STORAGE` - 存储权限（Android 11+）
- `MEDIA_PROJECTION` - 屏幕录制权限（运行时申请）

## 使用说明

1. 安装 APK 文件
2. 打开应用，授予所需权限
3. 可根据需要启用悬浮窗
4. 设置录制模式（每次授权/权限复用）
5. 设置主题（浅色/深色/跟随系统）
6. 点击录制按钮开始录制

## 录制模式说明

### 模式 A：每次重新授权
- 每次停止录制后，需要重新授权才能再次录制
- 优点：更安全
- 缺点：每次需要重新授权

### 模式 B：权限复用
- 录制停止后，不需要重新授权即可再次录制
- 优点：更便捷
- 缺点：可能存在稳定性问题

## 项目结构

```
app/src/main/java/com/example/capture/
├── MainActivity.kt           # 主界面
├── PermissionActivity.kt    # 权限申请界面
├── helper/
│   ├── PermissionHelper.kt # 权限帮助类
│   └── SettingsManager.kt   # 设置管理
├── service/
│   └── ScreenRecordService.kt  # 录制服务
└── view/
    └── FloatingView.kt     # 悬浮窗
```

## 版本

当前版本：1.0.0

## 许可证

仅供学习交流使用。
