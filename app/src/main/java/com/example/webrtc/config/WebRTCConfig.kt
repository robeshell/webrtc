package com.example.webrtc.config

import org.webrtc.PeerConnection

/**
 * WebRTC配置管理类
 * 统一管理所有WebRTC相关的配置参数
 */
object WebRTCConfig {
    
    /**
     * 信令服务器配置
     */
    object Signaling {
        const val DEFAULT_SERVER_URL = "http://192.168.31.121:3000"
        const val CONNECTION_TIMEOUT = 10000L // 10秒
        const val HEARTBEAT_INTERVAL = 30000L // 30秒
        const val RECONNECT_INTERVAL = 5000L  // 5秒
        const val MAX_RECONNECT_ATTEMPTS = 5
    }
    
    /**
     * ICE服务器配置
     */
    object ICE {
        val DEFAULT_ICE_SERVERS = listOf(
            // Google公共STUN服务器
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            
            // 如果需要TURN服务器，请添加：
            // PeerConnection.IceServer.builder("turn:your-turn-server:3478")
            //     .setUsername("username")
            //     .setPassword("password")
            //     .createIceServer()
        )
    }
    
    /**
     * 视频编码配置
     */
    object Video {
        // 🚀 大幅提升App内容模式分辨率配置
        const val DEFAULT_WIDTH = 1920   // App内容模式提升到1080p宽度
        const val DEFAULT_HEIGHT = 1080  // App内容模式提升到1080p高度
        
        // 全屏模式超高画质配置
        const val FULLSCREEN_WIDTH = 1920   // 全屏模式高分辨率
        const val FULLSCREEN_HEIGHT = 1080  // 全屏模式高分辨率
        
        const val MAX_WIDTH = 1920
        const val MAX_HEIGHT = 1080
        
        // 🚀 提升App内容模式帧率
        const val DEFAULT_FPS = 30      // App内容模式提升到30fps
        const val FULLSCREEN_FPS = 30   // 全屏模式高帧率
        const val MAX_FPS = 60          // 最大帧率
        const val MIN_FPS = 10          // 最小帧率
        
        // 🚀 大幅提升App内容模式码率配置，追求高清传输
        const val DEFAULT_BITRATE = 8000    // App内容模式大幅提升到8Mbps
        const val FULLSCREEN_BITRATE = 12000 // 全屏模式提升到12Mbps，确保1080p质量
        const val MIN_BITRATE = 3000        // 最小码率提升到3Mbps
        const val MAX_BITRATE = 15000       // 最大码率提升到15Mbps
        
        // 编码器配置
        const val HARDWARE_ACCELERATION = true
        const val VIDEO_CODEC_H264 = "H264"
        const val VIDEO_CODEC_VP8 = "VP8"
        const val VIDEO_CODEC_VP9 = "VP9"
        const val PREFERRED_CODEC = VIDEO_CODEC_H264
    }
    
    /**
     * 音频编码配置
     */
    object Audio {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 1 // 单声道
        const val DEFAULT_BITRATE = 128 // kbps
        const val AUDIO_CODEC_OPUS = "opus"
        const val AUDIO_CODEC_PCMU = "PCMU"
        const val PREFERRED_CODEC = AUDIO_CODEC_OPUS
        
        // 音频采集参数
        const val AUDIO_RECORD_ERROR_RETRY_COUNT = 3
        const val AUDIO_TRACK_VOLUME = 1.0
    }
    
    /**
     * 屏幕捕获配置
     */
    object ScreenCapture {
        const val MEDIA_PROJECTION_REQUEST_CODE = 1001
        const val NOTIFICATION_ID = 1001
        
        // 屏幕捕获参数 - 优化画质
        const val VIRTUAL_DISPLAY_DPI = 320  // 保持合适的DPI
        const val CAPTURE_FORMAT = "I420" // YUV420格式
        
        // 画质增强参数
        const val QUALITY_HIGH = "high"
        const val QUALITY_ULTRA = "ultra"
        
        // 编码器优化参数
        const val KEY_FRAME_INTERVAL = 5  // 关键帧间隔（秒）
        const val PROFILE_BASELINE = "baseline"
        const val PROFILE_MAIN = "main"
        const val PROFILE_HIGH = "high"
        const val PREFERRED_PROFILE = PROFILE_HIGH  // 使用高画质Profile
        
        // 全屏模式专用高画质参数
        const val FULLSCREEN_TARGET_BITRATE = 6000000  // 6Mbps
        const val FULLSCREEN_MIN_BITRATE = 3000000     // 3Mbps最小
        const val FULLSCREEN_MAX_BITRATE = 8000000     // 8Mbps最大
        
        // 性能优化
        const val ENABLE_CPU_OVERUSE_DETECTION = true
        const val CPU_OVERUSE_THRESHOLD = 80 // CPU使用率阈值
        
        // 硬件加速优化
        const val PREFER_HARDWARE_ENCODING = true
        const val ENABLE_H264_HIGH_PROFILE = true
    }
    
    /**
     * 网络配置
     */
    object Network {
        // DTLS配置
        const val DTLS_SRTP_KEY_AGREEMENT_TIMEOUT = 10000L
        
        // ICE配置
        const val ICE_CONNECTION_RECEIVING_TIMEOUT = 10000
        const val ICE_BACKUP_CANDIDATE_PAIR_PING_INTERVAL = 5000
        const val KEY_TYPE = "ECDSA" // 密钥类型
        
        // 网络适应性
        const val ENABLE_DTLS = true
        const val ENABLE_RTP_DATA_CHANNEL = false
        const val ENABLE_DSCP = true
        const val ENABLE_IPV6 = true
    }
    
    /**
     * 调试配置
     */
    object Debug {
        const val ENABLE_LOGGING = true
        const val LOG_LEVEL = "INFO" // VERBOSE, DEBUG, INFO, WARN, ERROR
        const val ENABLE_METRICS = true
        const val METRICS_INTERVAL = 5000L // 5秒
        
        // 性能监控
        const val ENABLE_PERFORMANCE_MONITORING = true
        const val FRAME_RATE_MONITORING_INTERVAL = 1000L
    }
    
    /**
     * 用户界面配置
     */
    object UI {
        // 连接超时显示
        const val CONNECTION_TIMEOUT_DISPLAY = 15000L // 15秒
        
        // 自动重连提示
        const val SHOW_RECONNECT_DIALOG = true
        const val AUTO_RECONNECT_DELAY = 3000L // 3秒
        
        // 状态更新频率
        const val STATUS_UPDATE_INTERVAL = 1000L // 1秒
    }
}

/**
 * 动态配置类
 * 用于运行时调整配置参数
 */
data class DynamicConfig(
    var videoWidth: Int = WebRTCConfig.Video.DEFAULT_WIDTH,
    var videoHeight: Int = WebRTCConfig.Video.DEFAULT_HEIGHT,
    var videoFps: Int = WebRTCConfig.Video.DEFAULT_FPS,
    var videoBitrate: Int = WebRTCConfig.Video.DEFAULT_BITRATE,
    var audioBitrate: Int = WebRTCConfig.Audio.DEFAULT_BITRATE,
    var enableAudio: Boolean = true,
    var enableVideo: Boolean = true,
    var signalingServerUrl: String = WebRTCConfig.Signaling.DEFAULT_SERVER_URL,
    var enableHardwareAcceleration: Boolean = WebRTCConfig.Video.HARDWARE_ACCELERATION,
    var preferredVideoCodec: String = WebRTCConfig.Video.PREFERRED_CODEC,
    var preferredAudioCodec: String = WebRTCConfig.Audio.PREFERRED_CODEC
) {
    /**
     * 验证配置参数是否有效
     */
    fun validate(): Boolean {
        return videoWidth > 0 && videoHeight > 0 &&
                videoFps in WebRTCConfig.Video.MIN_FPS..WebRTCConfig.Video.MAX_FPS &&
                videoBitrate in WebRTCConfig.Video.MIN_BITRATE..WebRTCConfig.Video.MAX_BITRATE &&
                audioBitrate > 0 &&
                signalingServerUrl.isNotBlank()
    }
    
    /**
     * 获取安全的配置（确保参数在有效范围内）
     */
    fun getSafeConfig(): DynamicConfig {
        return copy(
            videoWidth = videoWidth.coerceIn(640, WebRTCConfig.Video.MAX_WIDTH),  // 提高最小宽度到640
            videoHeight = videoHeight.coerceIn(480, WebRTCConfig.Video.MAX_HEIGHT), // 提高最小高度到480
            videoFps = videoFps.coerceIn(WebRTCConfig.Video.MIN_FPS, WebRTCConfig.Video.MAX_FPS),
            videoBitrate = videoBitrate.coerceIn(WebRTCConfig.Video.MIN_BITRATE, WebRTCConfig.Video.MAX_BITRATE),
            audioBitrate = audioBitrate.coerceAtLeast(64)
        )
    }
} 
