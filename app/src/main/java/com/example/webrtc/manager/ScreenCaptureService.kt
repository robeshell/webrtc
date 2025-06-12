package com.example.webrtc.manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.webrtc.MainActivity
import com.example.webrtc.R

/**
 * 屏幕录制前台服务
 * 用于满足Android系统对媒体投影的前台服务要求
 */
class ScreenCaptureService : Service() {
    
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "屏幕录制服务创建")
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "启动屏幕录制前台服务")
        
        try {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
            Log.i(TAG, "前台服务已成功启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败", e)
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "屏幕录制服务销毁")
        stopForeground(true)
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕录制服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "WebRTC屏幕投屏服务正在运行"
                setShowBadge(false)
                setSound(null, null)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕投屏进行中")
            .setContentText("WebRTC正在进行屏幕投屏")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
} 