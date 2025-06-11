# 📱 Android WebRTC 屏幕投屏应用

基于WebRTC技术实现的Android屏幕投屏应用，支持将Android设备屏幕实时投屏到PC/Web端。

## 🎯 功能特性

### 核心功能
- ✅ **实时屏幕投屏**: 基于WebRTC的低延迟屏幕共享
- ✅ **P2P直连**: 点对点传输，减少服务器压力
- ✅ **音频传输**: 可选的系统音频捕获和传输
- ✅ **自适应码率**: 根据网络状况动态调整视频质量
- ✅ **多分辨率支持**: 720p/1080p/4K分辨率选择
- ✅ **硬件加速**: 优先使用硬件编解码器

### 技术特性
- 🚀 **高性能**: 硬件加速编码，CPU使用率优化
- 🔐 **安全传输**: WebRTC内置加密（DTLS/SRTP）
- 🌐 **跨平台兼容**: 支持与Web端、PC端互联
- 📱 **Material Design**: 现代化的用户界面设计
- 🔄 **自动重连**: 网络异常时自动重连机制

## 🏗️ 系统架构

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Android App   │    │  Signaling      │    │   PC/Web        │
│                 │    │  Server         │    │   Viewer        │
│ ┌─────────────┐ │    │                 │    │                 │
│ │ Screen      │ │    │ ┌─────────────┐ │    │ ┌─────────────┐ │
│ │ Capture     │ │◄──►│ │ WebSocket   │ │◄──►│ │ WebRTC      │ │
│ │ Manager     │ │    │ │ Signaling   │ │    │ │ Decoder     │ │
│ └─────────────┘ │    │ └─────────────┘ │    │ └─────────────┘ │
│ ┌─────────────┐ │    │                 │    │                 │
│ │ WebRTC      │ │    │ ┌─────────────┐ │    │ ┌─────────────┐ │
│ │ Manager     │ │    │ │ Room        │ │    │ │ Video       │ │
│ │             │ │    │ │ Management  │ │    │ │ Display     │ │
│ └─────────────┘ │    │ └─────────────┘ │    │ └─────────────┘ │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

## 🔧 技术栈

### Android端
- **WebRTC**: `org.webrtc:google-webrtc:1.0.32006`
- **屏幕捕获**: MediaProjection API
- **信令通信**: Java-WebSocket
- **UI框架**: Jetpack Compose + Material Design 3
- **权限管理**: PermissionX
- **网络库**: OkHttp
- **JSON解析**: Gson
- **协程**: Kotlin Coroutines

### 核心组件
- **WebRTCManager**: WebRTC连接生命周期管理
- **ScreenCaptureManager**: 屏幕捕获和权限管理
- **SignalingManager**: WebSocket信令通信
- **DynamicConfig**: 运行时配置管理

## 📱 应用界面

### 主界面功能
- **连接状态显示**: 实时显示信令服务器、WebRTC连接、屏幕共享状态
- **一键投屏**: 简单的开始/停止投屏按钮
- **房间管理**: 房间ID生成和管理
- **服务器连接**: 信令服务器连接控制
- **错误提示**: 友好的错误信息显示

### 状态指示
- 🟢 **绿色**: 连接正常/功能运行中
- 🟡 **黄色**: 连接中/准备中
- 🔴 **红色**: 连接失败/错误状态
- ⚪ **灰色**: 未连接/空闲状态

## ⚙️ 配置说明

### 视频配置
```kotlin
object Video {
    const val DEFAULT_WIDTH = 1280      // 默认宽度
    const val DEFAULT_HEIGHT = 720      // 默认高度
    const val DEFAULT_FPS = 30         // 默认帧率
    const val DEFAULT_BITRATE = 2500   // 默认码率(kbps)
    const val HARDWARE_ACCELERATION = true  // 硬件加速
}
```

### 音频配置
```kotlin
object Audio {
    const val SAMPLE_RATE = 48000      // 采样率
    const val CHANNELS = 1             // 声道数
    const val DEFAULT_BITRATE = 128    // 默认码率(kbps)
    const val PREFERRED_CODEC = "opus" // 优选编码器
}
```

### 信令服务器配置
```kotlin
object Signaling {
    const val DEFAULT_SERVER_URL = "wss://your-signaling-server.com/ws"
    const val CONNECTION_TIMEOUT = 10000L      // 连接超时
    const val HEARTBEAT_INTERVAL = 30000L     // 心跳间隔
    const val RECONNECT_INTERVAL = 5000L      // 重连间隔
    const val MAX_RECONNECT_ATTEMPTS = 5      // 最大重连次数
}
```

## 🚀 快速开始

### 1. 环境要求
- Android Studio Hedgehog+ (2023.1.1+)
- Android SDK 24+ (Android 7.0+)
- Kotlin 1.9.0+
- Gradle 8.0+

### 2. 项目设置
```bash
# 克隆项目
git clone https://github.com/your-repo/webrtc-screen-share.git

# 打开Android Studio，导入项目
# 同步Gradle依赖
```

### 3. 信令服务器配置
在 `WebRTCConfig.kt` 中配置你的信令服务器地址：
```kotlin
const val DEFAULT_SERVER_URL = "wss://your-signaling-server.com/ws"
```

### 4. 构建和运行
```bash
# 构建Debug版本
./gradlew assembleDebug

# 安装到设备
./gradlew installDebug
```

## 📋 使用说明

### 开始投屏
1. **连接服务器**: 点击"连接服务器"按钮
2. **输入房间ID**: 输入或生成房间ID
3. **开始投屏**: 点击"开始投屏"按钮
4. **权限授权**: 授予屏幕录制权限
5. **分享房间ID**: 将房间ID分享给观看方

### 停止投屏
1. 点击"停止投屏"按钮
2. 应用会自动清理所有资源和连接

### 状态监控
- 实时查看连接状态
- 监控投屏质量和网络状况
- 查看连接的远程用户

## 🔒 权限说明

### 必需权限
- `INTERNET`: 网络通信
- `ACCESS_NETWORK_STATE`: 网络状态检查
- `ACCESS_WIFI_STATE`: WiFi状态检查
- `FOREGROUND_SERVICE`: 前台服务
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`: 媒体投影服务

### 可选权限
- `RECORD_AUDIO`: 音频录制（可选）
- `POST_NOTIFICATIONS`: 通知显示（Android 13+）
- `WAKE_LOCK`: 保持屏幕唤醒

## 🔧 自定义配置

### 修改视频参数
```kotlin
val config = DynamicConfig().apply {
    videoWidth = 1920
    videoHeight = 1080
    videoFps = 60
    videoBitrate = 5000
    enableHardwareAcceleration = true
}
```

### 修改网络设置
```kotlin
// 添加TURN服务器
val turnServer = PeerConnection.IceServer.builder("turn:your-turn-server:3478")
    .setUsername("username")
    .setPassword("password")
    .createIceServer()
```

## 🐛 故障排除

### 常见问题

#### 1. 无法连接信令服务器
- 检查网络连接
- 确认服务器地址正确
- 检查防火墙设置

#### 2. 屏幕录制权限被拒绝
- 在系统设置中手动授予权限
- 重启应用重新请求权限

#### 3. 投屏质量差
- 检查网络带宽
- 降低分辨率和帧率
- 启用硬件加速

#### 4. 连接频繁断开
- 检查网络稳定性
- 调整重连参数
- 使用TURN服务器

### 日志调试
```bash
# 查看应用日志
adb logcat | grep "WebRTC\|ScreenCapture\|Signaling"

# 过滤错误日志
adb logcat *:E | grep "com.example.webrtc"
```

## 🚦 性能优化

### 编码优化
- 优先使用硬件编码器
- 动态调整码率和分辨率
- 选择合适的编码格式

### 网络优化
- ICE候选优化
- 带宽自适应
- 拥塞控制

### 电池优化
- 合理使用前台服务
- 及时释放资源
- 优化CPU使用率

## 🛡️ 安全考虑

### 数据安全
- WebRTC内置端到端加密
- 信令服务器使用WSS协议
- 不存储用户屏幕内容

### 隐私保护
- 明确的权限请求
- 用户授权确认
- 屏幕录制状态提示

## 📄 开源协议

本项目基于 [MIT License](LICENSE) 开源协议。

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request 来帮助改进项目。

### 开发流程
1. Fork 本仓库
2. 创建功能分支
3. 提交代码
4. 创建 Pull Request

## 📞 技术支持

- **Issue 反馈**: [GitHub Issues](https://github.com/your-repo/issues)
- **功能建议**: 通过 Issue 提交
- **技术讨论**: [Discussions](https://github.com/your-repo/discussions)

## 📈 版本历史

### v1.0.0 (当前版本)
- ✅ 基础屏幕投屏功能
- ✅ WebRTC P2P连接
- ✅ 信令服务器通信
- ✅ Material Design界面
- ✅ 权限管理
- ✅ 自动重连机制

### 计划功能
- 🔄 多用户同时观看
- 🎮 远程控制功能
- 📊 性能监控面板
- 🎨 自定义主题
- 📱 横屏模式支持

---

> **提示**: 本应用需要信令服务器支持，请确保部署对应的WebSocket信令服务器。

> **注意**: 屏幕投屏可能会消耗较多电量和网络流量，建议在WiFi环境下使用。 