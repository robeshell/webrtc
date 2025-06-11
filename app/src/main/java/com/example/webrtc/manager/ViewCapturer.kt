package com.example.webrtc.manager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import org.webrtc.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 性能优化的View捕获器 - 轻量级录制
 */
class ViewCapturer(private val targetView: View) : VideoCapturer {
    
    companion object {
        private const val TAG = "ViewCapturer"
        private const val LOW_FPS = 5  // 降低到5fps避免卡顿
        private const val CAPTURE_WIDTH = 480  // 降低分辨率
        private const val CAPTURE_HEIGHT = 320
    }
    
    private var capturerObserver: CapturerObserver? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val isCapturing = AtomicBoolean(false)
    private var captureWidth = CAPTURE_WIDTH
    private var captureHeight = CAPTURE_HEIGHT
    private var captureFps = LOW_FPS
    
    private var captureExecutor: ScheduledExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameCounter = 0L
    
    // 复用Bitmap避免频繁创建
    private var captureBitmap: Bitmap? = null
    private var captureCanvas: Canvas? = null
    private val capturing = AtomicBoolean(false)
    
    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: android.content.Context?,
        capturerObserver: CapturerObserver?
    ) {
        Log.d(TAG, "初始化ViewCapturer - 性能优化模式")
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
    }
    
    override fun startCapture(width: Int, height: Int, framerate: Int) {
        Log.d(TAG, "开始捕获View内容: ${CAPTURE_WIDTH}x${CAPTURE_HEIGHT}@${LOW_FPS}fps (性能优化)")
        
        if (isCapturing.get()) {
            Log.w(TAG, "ViewCapturer已在运行中")
            return
        }
        
        // 固定使用低分辨率和低帧率
        captureWidth = CAPTURE_WIDTH
        captureHeight = CAPTURE_HEIGHT
        captureFps = LOW_FPS
        frameCounter = 0
        
        try {
            // 预创建Bitmap
            captureBitmap = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.RGB_565) // 使用RGB_565减少内存
            captureCanvas = Canvas(captureBitmap!!)
            
            isCapturing.set(true)
            
            // 创建单线程执行器
            captureExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "ViewCapturer-LowPriority").apply {
                    priority = Thread.MIN_PRIORITY // 设置低优先级
                }
            }
            
            // 低帧率定时捕获
            captureExecutor?.scheduleAtFixedRate(
                { captureFrameSafely() },
                0,
                1000L / captureFps,
                TimeUnit.MILLISECONDS
            )
            
            // 通知观察者
            capturerObserver?.onCapturerStarted(true)
            Log.i(TAG, "ViewCapturer启动成功 (轻量级模式)")
            
        } catch (e: Exception) {
            Log.e(TAG, "启动ViewCapturer失败", e)
            capturerObserver?.onCapturerStarted(false)
            cleanup()
        }
    }
    
    override fun stopCapture() {
        Log.d(TAG, "停止捕获View")
        
        if (!isCapturing.get()) {
            return
        }
        
        isCapturing.set(false)
        
        // 快速关闭
        captureExecutor?.shutdown()
        try {
            if (!captureExecutor?.awaitTermination(500, TimeUnit.MILLISECONDS)!!) {
                captureExecutor?.shutdownNow()
            }
        } catch (e: Exception) {
            captureExecutor?.shutdownNow()
        }
        captureExecutor = null
        
        cleanup()
        Log.i(TAG, "ViewCapturer已停止")
    }
    
    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        Log.d(TAG, "保持固定格式: ${CAPTURE_WIDTH}x${CAPTURE_HEIGHT}@${LOW_FPS}fps")
        // 保持固定低分辨率，忽略外部设置
    }
    
    override fun dispose() {
        Log.d(TAG, "释放ViewCapturer资源")
        stopCapture()
        surfaceTextureHelper?.dispose()
    }
    
    override fun isScreencast(): Boolean = true
    
    /**
     * 安全的帧捕获，避免阻塞
     */
    private fun captureFrameSafely() {
        if (!isCapturing.get() || capturing.get()) {
            return // 如果正在处理中，跳过这一帧
        }
        
        try {
            frameCounter++
            
            // 快速检查线程状态
            if (capturing.compareAndSet(false, true)) {
                // 异步处理，避免阻塞
                mainHandler.post {
                    captureViewQuickly()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "捕获帧失败", e)
            capturing.set(false)
        }
    }
    
    /**
     * 快速View捕获，最小化主线程占用
     */
    private fun captureViewQuickly() {
        if (!isCapturing.get() || captureBitmap == null || captureCanvas == null) {
            capturing.set(false)
            return
        }
        
        try {
            // 最简化的绘制
            captureCanvas!!.drawColor(Color.WHITE)
            
            // 获取View尺寸
            val viewWidth = targetView.width
            val viewHeight = targetView.height
            
            if (viewWidth > 0 && viewHeight > 0) {
                // 简单等比缩放
                val scale = minOf(
                    captureWidth.toFloat() / viewWidth,
                    captureHeight.toFloat() / viewHeight
                ) * 0.8f // 适当缩小避免裁剪
                
                val saveCount = captureCanvas!!.save()
                captureCanvas!!.scale(scale, scale)
                captureCanvas!!.translate(
                    (captureWidth / scale - viewWidth) / 2,
                    (captureHeight / scale - viewHeight) / 2
                )
                
                // 快速绘制View
                targetView.draw(captureCanvas!!)
                captureCanvas!!.restoreToCount(saveCount)
            } else {
                // 绘制简单提示
                drawSimpleText("View: ${viewWidth}x${viewHeight}")
            }
            
            // 异步转换，释放主线程
            captureExecutor?.execute {
                convertAndSendFrame()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "快速捕获失败", e)
            capturing.set(false)
        }
    }
    
    /**
     * 在后台线程转换并发送帧
     */
    private fun convertAndSendFrame() {
        try {
            val bitmap = captureBitmap ?: return
            
            // 简化的颜色转换
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            
            val i420Buffer = createSimpleI420Buffer(pixels, bitmap.width, bitmap.height)
            
            val timestampNs = System.nanoTime()
            val videoFrame = VideoFrame(i420Buffer, 0, timestampNs)
            
            capturerObserver?.onFrameCaptured(videoFrame)
            
            if (frameCounter % 25 == 0L) { // 每5秒打印一次
                Log.d(TAG, "已发送第${frameCounter}帧 (轻量级)")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "转换发送失败", e)
        } finally {
            capturing.set(false)
        }
    }
    
    /**
     * 绘制简单文字
     */
    private fun drawSimpleText(text: String) {
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }
        
        captureCanvas!!.drawText(
            text,
            captureWidth / 2f,
            captureHeight / 2f,
            paint
        )
    }
    
    /**
     * 简化的I420转换
     */
    private fun createSimpleI420Buffer(argbPixels: IntArray, width: Int, height: Int): VideoFrame.I420Buffer {
        val i420Buffer = JavaI420Buffer.allocate(width, height)
        
        try {
            val yPlane = i420Buffer.dataY
            val uPlane = i420Buffer.dataU
            val vPlane = i420Buffer.dataV
            
            // 快速RGB到Y转换
            for (i in argbPixels.indices) {
                val pixel = argbPixels[i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                
                // 简化的亮度计算
                val y = (r + g + b) / 3
                yPlane.put(i, y.toByte())
            }
            
            // 填充U/V为固定值（灰度效果）
            val uvSize = width * height / 4
            for (i in 0 until uvSize) {
                uPlane.put(i, 128.toByte())
                vPlane.put(i, 128.toByte())
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "I420转换失败", e)
        }
        
        return i420Buffer
    }
    
    /**
     * 清理资源
     */
    private fun cleanup() {
        try {
            captureBitmap?.recycle()
            captureBitmap = null
            captureCanvas = null
            capturing.set(false)
        } catch (e: Exception) {
            Log.e(TAG, "清理资源失败", e)
        }
    }
} 