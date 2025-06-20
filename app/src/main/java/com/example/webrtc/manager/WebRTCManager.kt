package com.example.webrtc.manager

import android.content.Context
import android.util.Log
import com.example.webrtc.config.DynamicConfig
import com.example.webrtc.config.WebRTCConfig
import com.example.webrtc.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.*

/**
 * WebRTC核心管理器
 * 负责WebRTC连接的创建、管理和销毁
 */
class WebRTCManager(
    private val context: Context,
    private var config: DynamicConfig = DynamicConfig()
) {
    
    companion object {
        private const val TAG = "WebRTCManager"
        private const val LOCAL_STREAM_ID = "local_stream"
        private const val VIDEO_TRACK_ID = "video_track"
        private const val AUDIO_TRACK_ID = "audio_track"
    }
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // WebRTC核心组件
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localMediaStream: MediaStream? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoCapturer: VideoCapturer? = null
    
    // 状态管理
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    
    // 回调接口
    private var eventCallback: WebRTCEventCallback? = null
    
    /**
     * 初始化WebRTC
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (_isInitialized.value) {
                Log.w(TAG, "WebRTC已经初始化")
                return@withContext true
            }

            // 创建PeerConnectionFactory
            setupPeerConnectionFactory()

            _isInitialized.value = true
            Log.i(TAG, "WebRTC初始化成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC初始化失败", e)
            false
        }
    }
    
    /**
     * 设置PeerConnectionFactory
     */
    private fun setupPeerConnectionFactory() {
        // 初始化PeerConnectionFactory
        val initializeOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
            
        PeerConnectionFactory.initialize(initializeOptions)
        
        // 创建支持高分辨率的编码器工厂
        val videoEncoderFactory = DefaultVideoEncoderFactory(
            EglBase.create().eglBaseContext,
            true,  // enableIntelVp8Encoder
            true   // enableH264HighProfile - 启用H.264 High Profile支持高分辨率
        )
        
        val videoDecoderFactory = DefaultVideoDecoderFactory(
            EglBase.create().eglBaseContext
        )
        
        // 设置高质量音频处理
        val audioProcessingFactory = JavaAudioDeviceModule.builder(context)
            .setSampleRate(WebRTCConfig.Audio.SAMPLE_RATE)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                    Log.w(TAG, "音频录制初始化错误: $errorMessage")
                }
                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                    errorMessage: String?
                ) {
                    Log.w(TAG, "音频录制启动错误: $errorMessage")
                }
                override fun onWebRtcAudioRecordError(errorMessage: String?) {
                    Log.w(TAG, "音频录制错误: $errorMessage")
                }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                    Log.w(TAG, "音频播放初始化错误: $errorMessage")
                }
                override fun onWebRtcAudioTrackStartError(
                    errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                    errorMessage: String?
                ) {
                    Log.w(TAG, "音频播放启动错误: $errorMessage")
                }
                override fun onWebRtcAudioTrackError(errorMessage: String?) {
                    Log.w(TAG, "音频播放错误: $errorMessage")
                }
            })
            .createAudioDeviceModule()
        
        // 设置PeerConnectionFactory选项以禁用自动降级
        val options = PeerConnectionFactory.Options().apply {
            // 禁用网络自适应功能，防止自动降低分辨率
            networkIgnoreMask = 0
            disableEncryption = false
            disableNetworkMonitor = false
        }
        
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .setAudioDeviceModule(audioProcessingFactory)
            .createPeerConnectionFactory()
    }
    
    /**
     * 创建PeerConnection
     */
    suspend fun createPeerConnection(): Boolean = withContext(Dispatchers.Main) {
        try {
            if (!_isInitialized.value) {
                Log.e(TAG, "WebRTC未初始化")
                return@withContext false
            }

            // 强制禁用所有自适应机制的RTCConfiguration
            val rtcConfig = PeerConnection.RTCConfiguration(WebRTCConfig.ICE.DEFAULT_ICE_SERVERS).apply {
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                keyType = PeerConnection.KeyType.ECDSA
                
                // 禁用所有可能导致分辨率降级的机制
                iceConnectionReceivingTimeout = -1  // 禁用ICE超时降级
                iceBackupCandidatePairPingInterval = -1  // 禁用备用候选降级
            }
            
            peerConnection = peerConnectionFactory?.createPeerConnection(
                rtcConfig,
                PeerConnectionObserver()
            )
            
            // 创建后立即设置发送参数约束
            peerConnection?.let { pc ->
                // 获取所有发送器并设置参数
                val senders = pc.senders
                
                for (sender in senders) {
                    val track = sender.track()
                    if (track != null && track.kind() == "video") {
                        try {
                            // 获取当前参数
                            val parameters = sender.parameters
                            
                            // 设置编码参数
                            if (parameters.encodings.isNotEmpty()) {
                                val encoding = parameters.encodings[0]
                                
                                // 强制设置分辨率和码率
                                encoding.maxBitrateBps = config.videoBitrate * 1000  // 转换为bps
                                encoding.minBitrateBps = config.videoBitrate * 800   // 80%最小码率
                                encoding.maxFramerate = config.videoFps  // 直接使用Int类型
                                
                                // 禁用自适应机制
                                encoding.scaleResolutionDownBy = 1.0  // 禁用分辨率缩放
                                encoding.active = true
                                
                                // 应用参数
                                sender.parameters = parameters
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "设置RTP参数失败", e)
                        }
                    }
                }
            }
            
            _connectionState.value = ConnectionState.CONNECTING
            Log.i(TAG, "PeerConnection创建成功")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "创建PeerConnection失败", e)
            false
        }
    }
    
    /**
     * 设置屏幕捕获器
     */
    fun setScreenCapturer(capturer: VideoCapturer) {
        videoCapturer = capturer
        Log.d(TAG, "设置屏幕捕获器")
    }
    
    /**
     * 创建本地媒体流
     */
    suspend fun createLocalMediaStream(): Boolean = withContext(Dispatchers.Main) {
        try {
            val factory = peerConnectionFactory ?: return@withContext false
            val pc = peerConnection ?: return@withContext false
            
            // 创建媒体流
            localMediaStream = factory.createLocalMediaStream(LOCAL_STREAM_ID)
            
            // 创建视频轨道和音频轨道
            val videoTrack = createVideoTrack()
            val audioTrack = createAudioTrack()
            
            // 添加视频轨道到PeerConnection
            videoTrack?.let { track ->
                val rtpSender = pc.addTrack(track, listOf(LOCAL_STREAM_ID))
            }
            
            // 添加音频轨道到PeerConnection
            audioTrack?.let { track ->
                val rtpSender = pc.addTrack(track, listOf(LOCAL_STREAM_ID))
            }
            
            Log.i(TAG, "本地媒体流创建成功")
            
            // 媒体流添加后立即设置RTP参数
            delay(100) // 稍等一下确保发送器已创建
            
            val senders = pc.senders
            
            for (sender in senders) {
                val track = sender.track()
                if (track != null && track.kind() == "video") {
                    try {
                        val parameters = sender.parameters
                        
                        if (parameters.encodings.isNotEmpty()) {
                            val encoding = parameters.encodings[0]
                            
                            // 强制设置最严格的参数
                            encoding.maxBitrateBps = config.videoBitrate * 1000
                            encoding.minBitrateBps = config.videoBitrate * 900  // 90%最小码率，更严格
                            encoding.maxFramerate = config.videoFps  // 直接使用Int类型
                            encoding.scaleResolutionDownBy = 1.0  // 绝对禁用缩放
                            encoding.active = true
                            
                            // 应用参数
                            sender.parameters = parameters
                            
                        } else {
                            Log.w(TAG, "编码参数列表为空")
                        }
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "设置RTP参数失败", e)
                    }
                }
            }
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "创建本地媒体流失败", e)
            false
        }
    }
    
    /**
     * 创建视频轨道
     */
    private fun createVideoTrack(): VideoTrack? {
        val factory = peerConnectionFactory ?: return null
        val capturer = videoCapturer ?: return null
        
        // 创建视频源
        videoSource = factory.createVideoSource(false)
        
        // 初始化视频捕获器
        capturer.initialize(
            SurfaceTextureHelper.create("CaptureThread", EglBase.create().eglBaseContext),
            context,
            videoSource?.capturerObserver
        )
        
        capturer.startCapture(config.videoWidth, config.videoHeight, config.videoFps)
        
        // 设置视频约束，防止WebRTC自动降级分辨率
        try {
            // 使用changeCaptureFormat强制确认分辨率
            capturer.changeCaptureFormat(config.videoWidth, config.videoHeight, config.videoFps)
            
            // 多次强制设置，确保生效
            Thread.sleep(50)
            capturer.changeCaptureFormat(config.videoWidth, config.videoHeight, config.videoFps)
            
            Thread.sleep(50)
            capturer.changeCaptureFormat(config.videoWidth, config.videoHeight, config.videoFps)
            
        } catch (e: Exception) {
            Log.w(TAG, "分辨率强制确认失败，但继续创建视频轨道", e)
        }
        
        // 创建视频轨道
        val videoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        videoTrack.setEnabled(true)
        
        // 设置视频轨道的分辨率约束（如果支持）
        try {
            // 注意：VideoTrack本身不支持直接设置分辨率约束
            // 分辨率主要由VideoCapturer控制
        } catch (e: Exception) {
            Log.w(TAG, "视频轨道约束设置失败", e)
        }
        
        // 添加到媒体流
        localMediaStream?.addTrack(videoTrack)
        
        return videoTrack
    }
    
    /**
     * 创建音频轨道
     */
    private fun createAudioTrack(): AudioTrack? {
        val factory = peerConnectionFactory ?: return null
        
        // 音频约束
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        
        // 创建音频源
        audioSource = factory.createAudioSource(audioConstraints)
        
        // 创建音频轨道
        val audioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        audioTrack.setEnabled(true)
        audioTrack.setVolume(WebRTCConfig.Audio.AUDIO_TRACK_VOLUME)
        
        // 添加到媒体流
        localMediaStream?.addTrack(audioTrack)
        
        Log.d(TAG, "音频轨道创建成功")
        return audioTrack
    }
    
    /**
     * 创建Offer
     */
    suspend fun createOffer(): SessionDescription? = suspendCancellableCoroutine { continuation ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                Log.d(TAG, "Offer创建成功")
                continuation.resume(sessionDescription) {}
            }
            
            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Offer创建失败: $error")
                continuation.resume(null) {}
            }
            
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }
    
    /**
     * 创建Answer
     */
    suspend fun createAnswer(offer: SessionDescription): SessionDescription? = suspendCancellableCoroutine { continuation ->
        // 设置远程描述
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "远程描述设置成功")
                
                // 创建Answer
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                }
                
                peerConnection?.createAnswer(object : SdpObserver {
                                override fun onCreateSuccess(sessionDescription: SessionDescription) {
                Log.d(TAG, "Answer创建成功")
                continuation.resume(sessionDescription) {}
            }
                    
                    override fun onCreateFailure(error: String) {
                        Log.e(TAG, "Answer创建失败: $error")
                        continuation.resume(null) {}
                    }
                    
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String) {}
                }, constraints)
            }
            
            override fun onSetFailure(error: String) {
                Log.e(TAG, "设置远程描述失败: $error")
                continuation.resume(null) {}
            }
            
            override fun onCreateSuccess(sessionDescription: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, offer)
    }
    
    /**
     * 设置本地描述
     */
    suspend fun setLocalDescription(sessionDescription: SessionDescription): Boolean = 
        suspendCancellableCoroutine { continuation ->
            peerConnection?.setLocalDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d(TAG, "本地描述设置成功")
                    continuation.resume(true) {}
                }
                
                override fun onSetFailure(error: String) {
                    Log.e(TAG, "设置本地描述失败: $error")
                    continuation.resume(false) {}
                }
                
                override fun onCreateSuccess(sessionDescription: SessionDescription) {}
                override fun onCreateFailure(error: String) {}
            }, sessionDescription)
        }
    
    /**
     * 设置远程描述
     */
    suspend fun setRemoteDescription(sessionDescription: SessionDescription): Boolean = 
        suspendCancellableCoroutine { continuation ->
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d(TAG, "远程描述设置成功")
                    continuation.resume(true) {}
                }
                
                override fun onSetFailure(error: String) {
                    Log.e(TAG, "设置远程描述失败: $error")
                    continuation.resume(false) {}
                }
                
                override fun onCreateSuccess(sessionDescription: SessionDescription) {}
                override fun onCreateFailure(error: String) {}
            }, sessionDescription)
        }
    
    /**
     * 添加ICE候选
     */
    fun addIceCandidate(iceCandidate: IceCandidate) {
        peerConnection?.addIceCandidate(iceCandidate)
    }
    
    /**
     * 设置事件回调
     */
    fun setEventCallback(callback: WebRTCEventCallback) {
        eventCallback = callback
    }
    
    /**
     * 停止屏幕捕获
     */
    fun stopScreenCapture() {
        try {
            Log.d(TAG, "安全停止屏幕捕获")
            
            videoCapturer?.let { capturer ->
                try {
                    // 先检查捕获器类型，分别处理
                    when (capturer) {
                        is org.webrtc.ScreenCapturerAndroid -> {
                            Log.d(TAG, "停止ScreenCapturerAndroid")
                            // 使用更安全的方式停止
                            try {
                                capturer.stopCapture()
                            } catch (e: Exception) {
                                Log.w(TAG, "ScreenCapturerAndroid停止时出现异常，忽略: ${e.message}")
                                // 不重新抛出异常，继续清理流程
                            }
                        }
                        else -> {
                            Log.d(TAG, "停止其他类型捕获器: ${capturer.javaClass.simpleName}")
                            capturer.stopCapture()
                        }
                    }
                    
                    // 安全地释放资源
                    try {
                        capturer.dispose()
                    } catch (e: Exception) {
                        Log.w(TAG, "释放捕获器资源时出现异常，忽略: ${e.message}")
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "停止捕获器时出现异常: ${e.message}", e)
                } finally {
                    // 确保清除引用
                    videoCapturer = null
                }
            }
            
            Log.d(TAG, "屏幕捕获已安全停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止屏幕捕获失败", e)
            // 强制清除引用，避免后续问题
            videoCapturer = null
        }
    }
    
    /**
     * 关闭连接
     */
    fun close() {
        Log.d(TAG, "开始关闭WebRTC连接")
        
        try {
            // 添加状态检查，避免重复关闭
            if (!_isInitialized.value) {
                Log.w(TAG, "WebRTC未初始化，跳过关闭流程")
                return
            }
            
            // 1. 首先安全停止屏幕捕获
            stopScreenCapture()
            
            // 2. 清理媒体流和轨道
            try {
                localMediaStream?.let { stream ->
                    // 使用更安全的清理方法
                    safeDisposeMediaStream(stream)
                }
                localMediaStream = null
            } catch (e: Exception) {
                Log.e(TAG, "清理媒体流失败", e)
                // 强制清除引用
                localMediaStream = null
            }
            
            // 3. 清理视频源
            try {
                videoSource?.dispose()
                videoSource = null
            } catch (e: Exception) {
                Log.w(TAG, "清理视频源异常: ${e.message}")
            }
            
            // 4. 清理音频源
            try {
                audioSource?.dispose()
                audioSource = null
            } catch (e: Exception) {
                Log.w(TAG, "清理音频源异常: ${e.message}")
            }
            
            // 5. 关闭PeerConnection
            try {
                peerConnection?.let { pc ->
                    // 检查连接状态，避免重复关闭
                    if (pc.connectionState() != PeerConnection.PeerConnectionState.CLOSED) {
                        Log.d(TAG, "关闭PeerConnection，当前状态: ${pc.connectionState()}")
                        pc.close()
                    } else {
                        Log.d(TAG, "PeerConnection已经是CLOSED状态")
                    }
                }
                peerConnection = null
            } catch (e: Exception) {
                Log.w(TAG, "关闭PeerConnection异常: ${e.message}")
                // 强制清除引用
                peerConnection = null
            }
            
            // 6. 最后清理工厂（注意：不要dispose，可能被其他组件使用）
            try {
                // 不直接dispose工厂，因为可能被复用
                peerConnectionFactory = null
            } catch (e: Exception) {
                Log.w(TAG, "清理工厂异常: ${e.message}")
            }
            
            // 7. 更新状态
            _connectionState.value = ConnectionState.CLOSED
            _isInitialized.value = false
            
            // 8. 取消协程（使用更安全的方式）
            try {
                if (!scope.isActive) {
                    Log.d(TAG, "协程作用域已经不活跃")
                } else {
                    scope.cancel("WebRTC关闭")
                }
            } catch (e: Exception) {
                Log.w(TAG, "取消协程异常: ${e.message}")
            }
            
            Log.i(TAG, "WebRTC连接已安全关闭")
            
        } catch (e: Exception) {
            Log.e(TAG, "关闭WebRTC连接时出现异常", e)
            // 强制重置状态
            _connectionState.value = ConnectionState.CLOSED
            _isInitialized.value = false
        }
    }
    
    /**
     * PeerConnection观察者
     */
    private inner class PeerConnectionObserver : PeerConnection.Observer {
        
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            Log.d(TAG, "信令状态变化: $state")
        }
        
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.d(TAG, "ICE连接状态变化: $state")
            
            scope.launch {
                _connectionState.value = when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> ConnectionState.CONNECTED
                    PeerConnection.IceConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTED
                    PeerConnection.IceConnectionState.FAILED -> ConnectionState.FAILED
                    PeerConnection.IceConnectionState.CLOSED -> ConnectionState.CLOSED
                    else -> ConnectionState.CONNECTING
                }
                
                eventCallback?.onConnectionStateChanged(_connectionState.value)
            }
        }
        
        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.d(TAG, "ICE连接接收状态: $receiving")
        }
        
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.d(TAG, "ICE收集状态变化: $state")
        }
        
        override fun onIceCandidate(iceCandidate: IceCandidate) {
            Log.d(TAG, "收到ICE候选: ${iceCandidate.sdp}")
            eventCallback?.onIceCandidate(iceCandidate)
        }
        
        override fun onIceCandidatesRemoved(iceCandidates: Array<out IceCandidate>) {
            Log.d(TAG, "ICE候选被移除: ${iceCandidates.size}")
        }
        
        override fun onAddStream(mediaStream: MediaStream) {
            Log.d(TAG, "收到远程媒体流")
            eventCallback?.onRemoteStreamAdded(mediaStream)
        }
        
        override fun onRemoveStream(mediaStream: MediaStream) {
            Log.d(TAG, "远程媒体流被移除")
            eventCallback?.onRemoteStreamRemoved(mediaStream)
        }
        
        override fun onDataChannel(dataChannel: DataChannel) {
            Log.d(TAG, "收到数据通道")
        }
        
        override fun onRenegotiationNeeded() {
            Log.d(TAG, "需要重新协商")
        }
        
        override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            Log.d(TAG, "收到远程轨道: ${rtpReceiver.track()?.kind()}")
        }
    }
    
    /**
     * 更新配置参数
     */
    fun updateConfig(newConfig: DynamicConfig) {
        config = newConfig
        Log.i(TAG, "配置已更新: ${config.videoWidth}×${config.videoHeight}@${config.videoFps}fps, ${config.videoBitrate}kbps")
    }
    
    /**
     * 获取当前配置
     */
    fun getCurrentConfig(): DynamicConfig = config
    
    /**
     * 安全地释放MediaStream，避免重复释放异常
     */
    private fun safeDisposeMediaStream(stream: MediaStream) {
        try {
            Log.d(TAG, "开始安全清理MediaStream")
            
            // 方法1：先尝试直接dispose，如果失败则手动清理
            try {
                stream.dispose()
                Log.d(TAG, "MediaStream直接dispose成功")
                return
            } catch (e: IllegalStateException) {
                Log.w(TAG, "MediaStream直接dispose失败，尝试手动清理: ${e.message}")
            }
            
            // 方法2：手动清理各个轨道
            try {
                // 安全地移除视频轨道
                val videoTracks = stream.videoTracks.toList() // 创建副本避免并发修改
                videoTracks.forEach { track ->
                    try {
                        if (track.state() != MediaStreamTrack.State.ENDED) {
                            track.setEnabled(false)
                            stream.removeTrack(track)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "移除视频轨道异常: ${e.message}")
                    }
                }
                
                // 安全地移除音频轨道
                val audioTracks = stream.audioTracks.toList() // 创建副本避免并发修改
                audioTracks.forEach { track ->
                    try {
                        if (track.state() != MediaStreamTrack.State.ENDED) {
                            track.setEnabled(false)
                            stream.removeTrack(track)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "移除音频轨道异常: ${e.message}")
                    }
                }
                
                // 最后再次尝试dispose
                stream.dispose()
                                    Log.d(TAG, "MediaStream手动清理成功")
                
            } catch (e: Exception) {
                Log.w(TAG, "MediaStream手动清理也失败，忽略: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "安全清理MediaStream完全失败", e)
        }
    }
}

/**
 * WebRTC事件回调接口
 */
interface WebRTCEventCallback {
    fun onConnectionStateChanged(state: ConnectionState)
    fun onIceCandidate(iceCandidate: IceCandidate)
    fun onRemoteStreamAdded(mediaStream: MediaStream)
    fun onRemoteStreamRemoved(mediaStream: MediaStream)
    fun onError(error: String)
} 