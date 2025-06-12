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
        private const val DEFAULT_FPS = 30  // 提升帧率到30fps
        private const val DEFAULT_WIDTH = 1920  // 🚀 提升到1080p分辨率
        private const val DEFAULT_HEIGHT = 1080
    }
    
    private var capturerObserver: CapturerObserver? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val isCapturing = AtomicBoolean(false)
    private var captureWidth = DEFAULT_WIDTH
    private var captureHeight = DEFAULT_HEIGHT
    private var captureFps = DEFAULT_FPS
    
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
        // 🚨 强制调试：立即打印接收到的参数
        Log.e(TAG, "🚨🚨🚨 ViewCapturer.startCapture 强制调试 🚨🚨🚨")
        Log.e(TAG, "📥 接收参数: width=$width, height=$height, framerate=$framerate")
        Log.e(TAG, "🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨")
        
        Log.i(TAG, "🎥 开始捕获View内容: ${width}×${height}@${framerate}fps")
        
        if (isCapturing.get()) {
            Log.w(TAG, "ViewCapturer已在运行中")
            return
        }
        
        // 🔧 关键修复：强制使用传入的高分辨率参数
        captureWidth = width.coerceAtLeast(1280)  // 最低1280像素宽度
        captureHeight = height.coerceAtLeast(720) // 最低720像素高度
        captureFps = framerate.coerceIn(15, 30)   // 帧率范围15-30fps
        frameCounter = 0
        
        Log.i(TAG, "✅ 实际捕获参数: ${captureWidth}×${captureHeight}@${captureFps}fps")
        Log.i(TAG, "📥 原始传入参数: ${width}×${height}@${framerate}fps")
        
        // 🔧 性能优化：预创建Bitmap避免运行时分配
        try {
            captureBitmap?.recycle()
            captureBitmap = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
            captureCanvas = Canvas(captureBitmap!!)
            Log.i(TAG, "✅ 预创建Bitmap成功: ${captureWidth}×${captureHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "预创建Bitmap失败", e)
            return
        }
        
        isCapturing.set(true)
        
        // 🔧 性能优化：使用单线程执行器，避免线程切换开销
        captureExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ViewCaptureThread").apply {
                priority = Thread.MAX_PRIORITY  // 最高优先级
                isDaemon = true
            }
        }
        
        // 🚨 关键：计算精确的捕获间隔
        val captureIntervalMs = (1000.0 / captureFps).toLong()
        Log.e(TAG, "⏱️ 捕获间隔: ${captureIntervalMs}ms (目标${captureFps}fps)")
        
        // 开始定时捕获
        captureExecutor?.scheduleAtFixedRate({
            if (isCapturing.get()) {
                try {
                    // 🔧 性能优化：直接在后台线程处理，减少主线程压力
                    captureFrameOptimized()
                } catch (e: Exception) {
                    Log.e(TAG, "捕获帧异常", e)
                }
            }
        }, 0, captureIntervalMs, TimeUnit.MILLISECONDS)
        
        Log.i(TAG, "✅ ViewCapturer启动成功，目标帧率: ${captureFps}fps")
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
        Log.i(TAG, "🔄 更改捕获格式: ${width}×${height}@${framerate}fps")
        
        if (isCapturing.get()) {
            // 🔧 动态更新参数（保持高分辨率）
            captureWidth = width.coerceAtLeast(1280)  // 最低1280像素宽度
            captureHeight = height.coerceAtLeast(720) // 最低720像素高度
            captureFps = framerate.coerceIn(15, 30)   // 帧率范围15-30fps
            
            // 重新创建Bitmap
            try {
                captureBitmap?.recycle()
                captureBitmap = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
                captureCanvas = Canvas(captureBitmap!!)
                Log.i(TAG, "✅ 捕获格式已更新: ${captureWidth}×${captureHeight}@${captureFps}fps")
            } catch (e: Exception) {
                Log.e(TAG, "更新捕获格式失败", e)
            }
        }
    }
    
    override fun dispose() {
        Log.d(TAG, "🧹 释放ViewCapturer资源...")
        
        try {
            // 先停止捕获
            stopCapture()
            
            // ViewCapturer不使用SurfaceTextureHelper，所以不需要dispose它
            // 只清除引用即可
            surfaceTextureHelper = null
            
            Log.i(TAG, "✅ ViewCapturer资源释放完成")
        } catch (e: Exception) {
            Log.e(TAG, "释放ViewCapturer资源失败", e)
        }
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
                    captureFrame()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "捕获帧失败", e)
            capturing.set(false)
        }
    }
    
    /**
     * 捕获单帧 - 从View创建Bitmap并转换为I420
     */
    private fun captureFrame() {
        if (capturing.get()) return
        capturing.set(true)
        
        try {
            mainHandler.post {
                try {
                    // 🚨 强制调试：开始捕获帧
                    Log.e(TAG, "🚨🚨🚨 captureFrame 开始 - 帧#$frameCounter 🚨🚨🚨")
                    Log.e(TAG, "📐 目标尺寸: ${captureWidth}×${captureHeight}")
                    
                    // 创建或复用高分辨率Bitmap
                    if (captureBitmap == null || 
                        captureBitmap?.width != captureWidth || 
                        captureBitmap?.height != captureHeight) {
                        
                        captureBitmap?.recycle() // 回收旧bitmap
                        
                        Log.e(TAG, "🎨 创建新Bitmap: ${captureWidth}×${captureHeight}")
                        captureBitmap = Bitmap.createBitmap(
                            captureWidth, 
                            captureHeight, 
                            Bitmap.Config.ARGB_8888
                        )
                        captureCanvas = Canvas(captureBitmap!!)
                    }
                    
                    val bitmap = captureBitmap!!
                    val canvas = captureCanvas!!
                    
                    // 🚨 强制确认Bitmap尺寸
                    Log.e(TAG, "✅ Bitmap实际尺寸: ${bitmap.width}×${bitmap.height}")
                    
                    // 清空画布为白色背景
                    canvas.drawColor(Color.WHITE)
                    
                    // 绘制目标View（缩放适应）
                    canvas.save()
                    
                    val scaleX = captureWidth.toFloat() / targetView.width
                    val scaleY = captureHeight.toFloat() / targetView.height
                    canvas.scale(scaleX, scaleY)
                    
                    Log.e(TAG, "🔍 View原始尺寸: ${targetView.width}×${targetView.height}")
                    Log.e(TAG, "📏 缩放因子: scaleX=$scaleX, scaleY=$scaleY")
                    
                    targetView.draw(canvas)
                    canvas.restore()
                    
                    // 转换为I420格式
                    val i420Buffer = convertBitmapToI420(bitmap)
                    
                    // 🚨 强制确认I420Buffer尺寸
                    Log.e(TAG, "🎞️ I420Buffer实际尺寸: ${i420Buffer.width}×${i420Buffer.height}")
                    Log.e(TAG, "🚨🚨🚨 captureFrame 完成 - 帧#$frameCounter 🚨🚨🚨")
                    
                    frameCounter++
                    
                    // 发送到WebRTC
                    val timestampNs = System.nanoTime()
                    val videoFrame = VideoFrame(i420Buffer, 0, timestampNs)
                    capturerObserver?.onFrameCaptured(videoFrame)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "捕获帧失败", e)
                } finally {
                    capturing.set(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "捕获帧失败", e)
            capturing.set(false)
        }
    }
    
    /**
     * 在后台线程转换并发送帧
     */
    private fun convertAndSendFrame() {
        try {
            val bitmap = captureBitmap ?: return
            
            // 🔧 关键修复：确保使用正确的分辨率
            Log.d(TAG, "🎯 转换帧: Bitmap=${bitmap.width}×${bitmap.height}, 目标=${captureWidth}×${captureHeight}")
            
            // 确保bitmap尺寸与目标分辨率一致
            if (bitmap.width != captureWidth || bitmap.height != captureHeight) {
                Log.w(TAG, "⚠️ 分辨率不匹配！将使用Bitmap实际尺寸: ${bitmap.width}×${bitmap.height}")
            }
            
            // 获取像素数据
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            
            // 使用修复的I420转换
            val i420Buffer = createSimpleI420Buffer(pixels, bitmap.width, bitmap.height)
            
            val timestampNs = System.nanoTime()
            val videoFrame = VideoFrame(i420Buffer, 0, timestampNs)
            
            // 发送帧到WebRTC
            capturerObserver?.onFrameCaptured(videoFrame)
            
            // 🎯 增强的日志：显示实际发送的分辨率
            if (frameCounter % 25 == 0L) { // 每5秒打印一次
                Log.i(TAG, "📺 已发送第${frameCounter}帧: ${bitmap.width}×${bitmap.height} -> WebRTC")
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
     * 🔧 修复的I420转换 - 使用正确的YUV420格式
     */
    private fun createSimpleI420Buffer(argbPixels: IntArray, width: Int, height: Int): VideoFrame.I420Buffer {
        Log.d(TAG, "🎯 开始I420转换: ${width}×${height}")
        
        val i420Buffer = JavaI420Buffer.allocate(width, height)
        
        try {
            val yPlane = i420Buffer.dataY
            val uPlane = i420Buffer.dataU  
            val vPlane = i420Buffer.dataV
            
            // 🔧 关键修复：正确的YUV420转换
            var yIndex = 0
            var uvIndex = 0
            
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pixel = argbPixels[y * width + x]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    
                    // 🎯 使用标准的RGB到YUV转换公式（BT.601）
                    val yValue = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    yPlane.put(yIndex++, yValue.coerceIn(0, 255).toByte())
                    
                    // 🔧 关键修复：UV采样 - 每2x2像素取一个UV值
                    if (y % 2 == 0 && x % 2 == 0) {
                        val uValue = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val vValue = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        
                        uPlane.put(uvIndex, uValue.coerceIn(0, 255).toByte())
                        vPlane.put(uvIndex, vValue.coerceIn(0, 255).toByte())
                        uvIndex++
                    }
                }
            }
            
            Log.d(TAG, "✅ I420转换完成: Y=${yIndex}字节, UV=${uvIndex}字节")
            
        } catch (e: Exception) {
            Log.e(TAG, "I420转换失败", e)
        }
        
        return i420Buffer
    }
    
    /**
     * 🔧 修复I420转换算法 - 使用标准BT.601颜色空间转换
     */
    private fun convertBitmapToI420(bitmap: Bitmap): VideoFrame.I420Buffer {
        // 🚨 强制调试：开始I420转换
        Log.e(TAG, "🚨🚨🚨 convertBitmapToI420 开始 🚨🚨🚨")
        Log.e(TAG, "📥 输入Bitmap: ${bitmap.width}×${bitmap.height}")
        
        val width = bitmap.width
        val height = bitmap.height
        
        Log.e(TAG, "📤 输出I420: ${width}×${height}")
        
        // 创建I420Buffer
        val i420Buffer = JavaI420Buffer.allocate(width, height)
        
        // 提取像素数据
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        Log.e(TAG, "✅ 像素数据提取完成: ${pixels.size} 个像素")
        
        // 获取Y、U、V平面
        val yPlane = i420Buffer.dataY
        val uPlane = i420Buffer.dataU
        val vPlane = i420Buffer.dataV
        
        val yStride = i420Buffer.strideY
        val uStride = i420Buffer.strideU
        val vStride = i420Buffer.strideV
        
        Log.e(TAG, "📏 Y平面步长: $yStride, U平面步长: $uStride, V平面步长: $vStride")
        
        // 🔧 使用标准BT.601 RGB->YUV转换矩阵
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixelIndex = y * width + x
                val pixel = pixels[pixelIndex]
                
                // 提取RGB分量（ARGB格式）
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                
                // BT.601标准转换公式 (限制范围16-235)
                val yValue = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val uValue = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val vValue = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                
                // 设置Y值（每个像素都有）
                yPlane.put(y * yStride + x, yValue.coerceIn(16, 235).toByte())
                
                // U和V值（2x2采样，即每4个像素取一个UV值）
                if (y % 2 == 0 && x % 2 == 0) {
                    val uvY = y / 2
                    val uvX = x / 2
                    
                    uPlane.put(uvY * uStride + uvX, uValue.coerceIn(16, 240).toByte())
                    vPlane.put(uvY * vStride + uvX, vValue.coerceIn(16, 240).toByte())
                }
            }
        }
        
        Log.e(TAG, "✅ I420转换完成: Y平面=${yPlane.capacity()}字节, U平面=${uPlane.capacity()}字节, V平面=${vPlane.capacity()}字节")
        Log.e(TAG, "🚨🚨🚨 convertBitmapToI420 结束 🚨🚨🚨")
        
        return i420Buffer
    }
    
    /**
     * 🔧 性能优化的帧捕获方法
     */
    private fun captureFrameOptimized() {
        if (capturing.get()) return
        capturing.set(true)
        
        try {
            // 🔧 性能优化：在主线程快速绘制，在后台线程处理转换
            mainHandler.post {
                try {
                    val bitmap = captureBitmap ?: return@post
                    val canvas = captureCanvas ?: return@post
                    
                    // 🚨 快速绘制（最小化主线程时间）
                    canvas.drawColor(Color.WHITE)
                    
                    // 计算缩放
                    val scaleX = captureWidth.toFloat() / targetView.width
                    val scaleY = captureHeight.toFloat() / targetView.height
                    
                    canvas.save()
                    canvas.scale(scaleX, scaleY)
                    targetView.draw(canvas)
                    canvas.restore()
                    
                    // 🔧 关键：立即在后台线程处理I420转换
                    captureExecutor?.execute {
                        try {
                            val i420Buffer = convertBitmapToI420Fast(bitmap)
                            
                            frameCounter++
                            
                            // 发送到WebRTC
                            val timestampNs = System.nanoTime()
                            val videoFrame = VideoFrame(i420Buffer, 0, timestampNs)
                            capturerObserver?.onFrameCaptured(videoFrame)
                            
                            // 🚨 性能监控：每30帧打印一次性能信息
                            if (frameCounter % 30 == 0L) {
                                Log.d(TAG, "📊 性能统计: 已捕获${frameCounter}帧, 分辨率${captureWidth}×${captureHeight}")
                            }
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "I420转换失败", e)
                        } finally {
                            capturing.set(false)
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "绘制失败", e)
                    capturing.set(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "捕获帧失败", e)
            capturing.set(false)
        }
    }
    
    /**
     * 🔧 快速I420转换（性能优化版本）
     */
    private fun convertBitmapToI420Fast(bitmap: Bitmap): VideoFrame.I420Buffer {
        val width = bitmap.width
        val height = bitmap.height
        
        // 创建I420Buffer
        val i420Buffer = JavaI420Buffer.allocate(width, height)
        
        // 🔧 性能优化：批量处理像素
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // 获取平面
        val yPlane = i420Buffer.dataY
        val uPlane = i420Buffer.dataU
        val vPlane = i420Buffer.dataV
        
        val yStride = i420Buffer.strideY
        val uStride = i420Buffer.strideU
        val vStride = i420Buffer.strideV
        
        // 🔧 性能优化：使用更高效的转换算法
        var pixelIndex = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[pixelIndex++]
                
                // 快速RGB提取
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                
                // 快速YUV转换（简化版本，提高性能）
                val yValue = (77 * r + 150 * g + 29 * b) shr 8
                
                // 设置Y值
                yPlane.put(y * yStride + x, yValue.coerceIn(16, 235).toByte())
                
                // UV采样（2x2）
                if (y % 2 == 0 && x % 2 == 0) {
                    val uValue = ((-43 * r - 84 * g + 127 * b) shr 8) + 128
                    val vValue = ((127 * r - 106 * g - 21 * b) shr 8) + 128
                    
                    val uvY = y / 2
                    val uvX = x / 2
                    
                    uPlane.put(uvY * uStride + uvX, uValue.coerceIn(16, 240).toByte())
                    vPlane.put(uvY * vStride + uvX, vValue.coerceIn(16, 240).toByte())
                }
            }
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