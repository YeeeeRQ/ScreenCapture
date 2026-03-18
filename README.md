# Screen Recorder

Android 屏幕录制应用，支持悬浮窗控制、录制中截图和浅色/深色主题。

## 功能特性

- 屏幕录制（无需音频）
- 录制过程中即时截图（PNG格式，高清无损）
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
- MediaCodec（H.264 视频编码）
- OpenGL ES（截图渲染管线）
- SurfaceTexture + FBO
- Foreground Service

## 技术架构

### 录制+截图方案（MediaCodec + OpenGL ES FBO）

```
┌─────────────────────────────────────────────────────────────┐
│                      MediaProjection                         │
└──────────────────────────┬──────────────────────────────────┘
                           │ 创建
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                     VirtualDisplay                           │
└──────────────────────────┬──────────────────────────────────┘
                           │ 输出 Surface
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                    SurfaceTexture                            │
│              (GPU 纹理作为中间桥梁)                           │
└──────────────────────────┬──────────────────────────────────┘
                           │ updateTexImage()
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                  OpenGL Texture (OES)                       │
└──────────────────────────┬──────────────────────────────────┘
                           │
           ┌───────────────┴───────────────┐
           │         渲染线程              │
           ▼                               ▼
┌─────────────────────┐         ┌─────────────────────┐
│   Encoder Surface   │         │   FrameBuffer (FBO) │
│     (录制分支)       │         │     (截图分支)       │
└─────────┬───────────┘         └─────────┬───────────┘
          │                               │
          ▼                               ▼
┌─────────────────────┐         ┌─────────────────────┐
│     MediaCodec      │         │   glReadPixels()    │
│   (H.264 编码)      │         │   (读取像素)         │
└─────────┬───────────┘         └─────────┬───────────┘
          │                               │
          ▼                               ▼
┌─────────────────────┐         ┌─────────────────────┐
│     MediaMuxer      │         │      Bitmap        │
│    (封装 MP4)       │         │    (保存 PNG)      │
└─────────┬───────────┘         └─────────────────────┘
          │
          ▼
    ┌───────────┐
    │ .mp4 文件 │
    └───────────┘
```

### 方案优势

- **零延迟**：截图即所得
- **高清画质**：直接获取原始渲染帧，无压缩损失
- **不干扰录制**：GPU 并行处理，不影响视频编码流畅度
- **线程安全**：渲染线程独立处理，截图请求异步执行

## 截图功能说明

- **触发方式**：录制过程中点击悬浮窗的截图按钮
- **保存位置**：`Pictures/ScreenRecord` 目录
- **图片格式**：PNG（无损压缩）
- **分辨率**：与屏幕分辨率一致
- **技术实现**：OpenGL ES FBO 方案
  - SurfaceTexture 作为视频流中间桥梁
  - GPU 零拷贝渲染，不影响录制性能
  - 截图请求在渲染线程异步处理

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
│   ├── PermissionHelper.kt  # 权限帮助类
│   └── SettingsManager.kt   # 设置管理
├── service/
│   └── ScreenRecordService.kt  # 录制服务（MediaCodec + OpenGL 渲染管线）
└── view/
    └── FloatingView.kt       # 悬浮窗（含截图按钮）
```

## 核心文件说明

### ScreenRecordService.kt
核心录制服务，包含：
- MediaCodec 视频编码
- OpenGL ES 渲染管线
- FBO 截图实现
- 渲染线程管理

### FloatingView.kt
悬浮窗控件，包含：
- 录制/停止按钮
- 计时器显示
- 截图按钮（录制时显示）

## 版本

当前版本：1.0.0

## 许可证

MIT License

Copyright (c) 2024 Screen Recorder

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
