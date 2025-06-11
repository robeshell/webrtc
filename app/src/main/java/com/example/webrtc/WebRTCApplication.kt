package com.example.webrtc

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import org.webrtc.PeerConnectionFactory

/**
 * WebRTC应用程序类
 * 负责初始化WebRTC环境和全局配置
 */
class WebRTCApplication : Application() {

    companion object {
        const val SCREEN_CAPTURE_CHANNEL_ID = "screen_capture_channel"
        const val WEBRTC_SERVICE_CHANNEL_ID = "webrtc_service_channel"
        
        lateinit var instance: WebRTCApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // 初始化WebRTC
        initializeWebRTC()
        
        // 创建通知渠道
        createNotificationChannels()
    }

    /**
     * 初始化WebRTC
     */
    private fun initializeWebRTC() {
        // 初始化PeerConnectionFactory
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(false)
            .setFieldTrials("")
            .createInitializationOptions()
        
        PeerConnectionFactory.initialize(initializationOptions)
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // 屏幕捕获通知渠道
            val screenCaptureChannel = NotificationChannel(
                SCREEN_CAPTURE_CHANNEL_ID,
                "屏幕投屏",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "屏幕投屏服务通知"
                setShowBadge(false)
            }
            
            // WebRTC服务通知渠道
            val webrtcServiceChannel = NotificationChannel(
                WEBRTC_SERVICE_CHANNEL_ID,
                "WebRTC通信",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "WebRTC通信服务通知"
                setShowBadge(false)
            }
            
            notificationManager.createNotificationChannel(screenCaptureChannel)
            notificationManager.createNotificationChannel(webrtcServiceChannel)
        }
    }
} 