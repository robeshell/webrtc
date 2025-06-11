package com.example.webrtc.model

/**
 * WebRTC连接状态枚举
 */
enum class ConnectionState {
    DISCONNECTED,       // 未连接
    CONNECTING,         // 连接中
    CONNECTED,          // 已连接
    RECONNECTING,       // 重连中
    FAILED,            // 连接失败
    CLOSED             // 连接关闭
}

/**
 * 屏幕投屏状态枚举
 */
enum class ScreenShareState {
    IDLE,              // 空闲
    PERMISSION_REQUIRED, // 需要权限
    PREPARING,         // 准备中
    SHARING,           // 投屏中
    STOPPED,           // 已停止
    ERROR              // 错误
}

/**
 * 信令服务器连接状态
 */
enum class SignalingState {
    DISCONNECTED,      // 未连接
    CONNECTING,        // 连接中
    CONNECTED,         // 已连接
    RECONNECTING,      // 重连中
    ERROR              // 连接错误
} 