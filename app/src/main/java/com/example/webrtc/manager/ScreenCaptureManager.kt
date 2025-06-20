package com.example.webrtc.manager

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.webrtc.config.WebRTCConfig
import com.example.webrtc.model.ScreenShareState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.VideoCapturer

/**
 * 屏幕捕获管理器
 * 负责Android屏幕录制权限申请和屏幕捕获功能
 */
class ScreenCaptureManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ScreenCaptureManager"
    }
    
    // 屏幕录制相关
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    
    // 状态管理
    private val _screenShareState = MutableStateFlow(ScreenShareState.IDLE)
    val screenShareState: StateFlow<ScreenShareState> = _screenShareState.asStateFlow()
    
    // 屏幕信息
    private val displayMetrics = DisplayMetrics()
    
    // 权限数据存储
    private var permissionResultCode: Int = 0
    private var permissionData: Intent? = null
    
    init {
        mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        updateDisplayMetrics()
    }
    
    /**
     * 更新显示器信息
     */
    private fun updateDisplayMetrics() {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getMetrics(displayMetrics)
    }
    
    /**
     * 请求屏幕录制权限
     */
    fun requestScreenCapturePermission(activity: Activity) {
        if (mediaProjectionManager == null) {
            Log.e(TAG, "MediaProjectionManager未初始化")
            _screenShareState.value = ScreenShareState.ERROR
            return
        }
        
        // 在请求新权限前，先清理之前的资源
        cleanupMediaProjection()
        
        // 先启动前台服务，确保在权限获取过程中服务已经运行
        try {
            startForegroundService()
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败", e)
            _screenShareState.value = ScreenShareState.ERROR
            return
        }
        
        _screenShareState.value = ScreenShareState.PERMISSION_REQUIRED
        
        // 稍微延迟以确保服务有时间启动
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val captureIntent = mediaProjectionManager!!.createScreenCaptureIntent()
            activity.startActivityForResult(
                captureIntent,
                WebRTCConfig.ScreenCapture.MEDIA_PROJECTION_REQUEST_CODE
            )
        }, 200) // 200ms延迟
    }
    
    /**
     * 处理权限请求结果
     */
    fun handleScreenCapturePermissionResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != WebRTCConfig.ScreenCapture.MEDIA_PROJECTION_REQUEST_CODE) {
            return false
        }
        
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "屏幕录制权限被拒绝")
            _screenShareState.value = ScreenShareState.ERROR
            return false
        }
        
        Log.i(TAG, "屏幕录制权限获取成功")
        
        try {
            // 确保之前的资源已清理
            cleanupMediaProjection()
            
            // 保存权限数据
            permissionResultCode = resultCode
            permissionData = data
            
            // 检测权限数据中的录制范围设置
            detectRecordingScope(data)
            
            // 不在这里创建MediaProjection，而是在createScreenCapturer中创建
            _screenShareState.value = ScreenShareState.PREPARING
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "处理屏幕录制权限结果失败", e)
            _screenShareState.value = ScreenShareState.ERROR
            return false
        }
    }
    
    /**
     * 检测用户在系统对话框中选择的录制范围
     */
    private fun detectRecordingScope(data: Intent) {
        try {
            // 检查Intent中的额外信息，判断用户是否选择了单应用录制
            val extras = data.extras
            if (extras != null) {
                // 一些厂商会在Intent中添加录制范围信息
                val recordingMode = extras.getString("recording_mode")
                val captureArea = extras.getString("capture_area") 
                val isAppOnly = extras.getBoolean("single_app", false)
                
                if (isAppOnly || recordingMode == "single_app" || captureArea == "app") {
                    Log.w(TAG, "检测到用户可能选择了单应用录制模式")
                }
            }
            
        } catch (e: Exception) {
            // 正常情况，忽略异常
        }
    }
    
    /**
     * 创建屏幕捕获器
     */
    fun createScreenCapturer(): VideoCapturer? {
        if (permissionData == null) {
            Log.e(TAG, "权限数据未初始化，无法创建屏幕捕获器")
            _screenShareState.value = ScreenShareState.ERROR
            return null
        }
        
        return try {
            // 确保之前的捕获器已清理
            screenCapturer?.let { oldCapturer ->
                try {
                    oldCapturer.stopCapture()
                    oldCapturer.dispose()
                } catch (e: Exception) {
                    Log.w(TAG, "清理旧捕获器时出现异常", e)
                }
                screenCapturer = null
            }
            
            // 创建新的ScreenCapturerAndroid，使用权限Intent
            screenCapturer = ScreenCapturerAndroid(
                permissionData!!, // 使用权限Intent，WebRTC内部会创建MediaProjection
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "MediaProjection停止回调")
                        _screenShareState.value = ScreenShareState.STOPPED
                        // 清理资源
                        cleanupMediaProjection()
                    }
                }
            )
            
            if (screenCapturer == null) {
                Log.e(TAG, "ScreenCapturerAndroid创建返回null")
                _screenShareState.value = ScreenShareState.ERROR
                return null
            }
            
            Log.i(TAG, "屏幕捕获器创建成功")
            _screenShareState.value = ScreenShareState.SHARING
            screenCapturer
            
        } catch (e: Exception) {
            Log.e(TAG, "创建屏幕捕获器异常", e)
            _screenShareState.value = ScreenShareState.ERROR
            // 清理资源
            cleanupMediaProjection()
            null
        }
    }
    
    /**
     * 清理MediaProjection相关资源
     */
    private fun cleanupMediaProjection() {
        try {
            // 停止屏幕捕获器
            screenCapturer?.let { capturer ->
                try {
                    capturer.stopCapture()
                    capturer.dispose()
                } catch (e: Exception) {
                    Log.w(TAG, "停止屏幕捕获器时出现异常", e)
                }
                screenCapturer = null
            }
            
            // 停止MediaProjection
            mediaProjection?.let { projection ->
                try {
                    projection.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "停止MediaProjection时出现异常", e)
                }
                mediaProjection = null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "清理MediaProjection资源时出现异常", e)
        }
    }
    
    /**
     * 停止屏幕捕获
     */
    fun stopScreenCapture() {
        try {
            // 清理MediaProjection资源
            cleanupMediaProjection()
            
            // 停止前台服务
            stopForegroundService()
            
            // 清理权限数据（避免重复使用过期token）
            permissionData = null
            permissionResultCode = 0
            
            _screenShareState.value = ScreenShareState.STOPPED
            
        } catch (e: Exception) {
            Log.e(TAG, "停止屏幕捕获失败", e)
            _screenShareState.value = ScreenShareState.ERROR
        }
    }
    
    /**
     * 获取屏幕尺寸
     */
    fun getScreenSize(): Pair<Int, Int> {
        updateDisplayMetrics()
        return Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
    }

    
    /**
     * 检查是否有屏幕录制权限
     */
    fun hasScreenCapturePermission(): Boolean {
        return permissionData != null && permissionResultCode == Activity.RESULT_OK
    }

    
    /**
     * 手动设置屏幕共享状态（用于ViewCapturer模式）
     */
    fun setScreenShareState(state: ScreenShareState) {
        _screenShareState.value = state
    }
    
    /**
     * 释放资源
     */
    fun release() {
        try {
            // 停止屏幕捕获
            stopScreenCapture()
            
            // 清理状态
            _screenShareState.value = ScreenShareState.IDLE
            
            Log.i(TAG, "屏幕捕获资源释放完成")
            
        } catch (e: Exception) {
            Log.e(TAG, "释放屏幕捕获资源失败", e)
        }
    }
    
    /**
     * 启动前台服务
     */
    private fun startForegroundService() {
        val intent = Intent(context, ScreenCaptureService::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
    
    /**
     * 停止前台服务
     */
    private fun stopForegroundService() {
        try {
            val intent = Intent(context, ScreenCaptureService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "停止前台服务失败", e)
        }
    }
}
