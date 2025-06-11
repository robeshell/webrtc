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
import java.util.*

/**
 * WebRTC核心管理器
 * 负责WebRTC连接的创建、管理和销毁
 */
class WebRTCManager(
    private val context: Context,
    private val config: DynamicConfig = DynamicConfig()
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
            Log.d(TAG, "初始化WebRTC")
            
            if (_isInitialized.value) {
                Log.w(TAG, "WebRTC已经初始化")
                return@withContext true
            }
            
            // 创建PeerConnectionFactory
            createPeerConnectionFactory()
            
            _isInitialized.value = true
            Log.i(TAG, "WebRTC初始化成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC初始化失败", e)
            false
        }
    }
    
    /**
     * 创建PeerConnectionFactory
     */
    private fun createPeerConnectionFactory() {
        val options = PeerConnectionFactory.Options()
        
        // 视频编码器工厂
        val videoEncoderFactory = DefaultVideoEncoderFactory(
            EglBase.create().eglBaseContext,
            config.enableHardwareAcceleration,
            true
        )
        
        // 视频解码器工厂
        val videoDecoderFactory = DefaultVideoDecoderFactory(
            EglBase.create().eglBaseContext
        )
        
        // 音频处理
        val audioProcessingFactory = org.webrtc.audio.JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        
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
            Log.d(TAG, "创建PeerConnection")
            
            if (!_isInitialized.value) {
                Log.e(TAG, "WebRTC未初始化")
                return@withContext false
            }
            
            val rtcConfig = PeerConnection.RTCConfiguration(WebRTCConfig.ICE.DEFAULT_ICE_SERVERS).apply {
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                keyType = PeerConnection.KeyType.ECDSA
                // 这些字段在新版本中可能不存在或已更改，先注释掉
                // enableDtlsSrtp = WebRTCConfig.Network.ENABLE_DTLS
                // enableRtpDataChannel = WebRTCConfig.Network.ENABLE_RTP_DATA_CHANNEL
            }
            
            peerConnection = peerConnectionFactory?.createPeerConnection(
                rtcConfig,
                PeerConnectionObserver()
            )
            
            _connectionState.value = ConnectionState.CONNECTING
            Log.i(TAG, "PeerConnection创建成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "创建PeerConnection失败", e)
            _connectionState.value = ConnectionState.FAILED
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
            Log.d(TAG, "创建本地媒体流")
            
            val factory = peerConnectionFactory ?: return@withContext false
            
            // 创建媒体流
            localMediaStream = factory.createLocalMediaStream(LOCAL_STREAM_ID)
            
            // 创建视频流
            if (config.enableVideo && videoCapturer != null) {
                createVideoTrack()
            }
            
            // 创建音频流
            if (config.enableAudio) {
                createAudioTrack()
            }
            
            // 使用AddTrack而不是AddStream（兼容Unified Plan）
            localMediaStream?.videoTracks?.forEach { videoTrack ->
                peerConnection?.addTrack(videoTrack, listOf(LOCAL_STREAM_ID))
                Log.d(TAG, "视频轨道添加到PeerConnection")
            }
            
            localMediaStream?.audioTracks?.forEach { audioTrack ->
                peerConnection?.addTrack(audioTrack, listOf(LOCAL_STREAM_ID))
                Log.d(TAG, "音频轨道添加到PeerConnection")
            }
            
            Log.i(TAG, "本地媒体流创建成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "创建本地媒体流失败", e)
            false
        }
    }
    
    /**
     * 创建视频轨道
     */
    private fun createVideoTrack() {
        val factory = peerConnectionFactory ?: return
        val capturer = videoCapturer ?: return
        
        // 创建视频源
        videoSource = factory.createVideoSource(false)
        
        // 初始化视频捕获器
        capturer.initialize(
            SurfaceTextureHelper.create("CaptureThread", EglBase.create().eglBaseContext),
            context,
            videoSource?.capturerObserver
        )
        
        // 开始捕获
        capturer.startCapture(config.videoWidth, config.videoHeight, config.videoFps)
        
        // 创建视频轨道
        val videoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        videoTrack.setEnabled(true)
        
        // 添加到媒体流
        localMediaStream?.addTrack(videoTrack)
        
        Log.d(TAG, "视频轨道创建成功: ${config.videoWidth}x${config.videoHeight}@${config.videoFps}fps")
    }
    
    /**
     * 创建音频轨道
     */
    private fun createAudioTrack() {
        val factory = peerConnectionFactory ?: return
        
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
        Log.d(TAG, "添加ICE候选: ${iceCandidate.sdp}")
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
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null
            Log.d(TAG, "屏幕捕获已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止屏幕捕获失败", e)
        }
    }
    
    /**
     * 关闭连接
     */
    fun close() {
        Log.d(TAG, "关闭WebRTC连接")
        
        // 停止屏幕捕获
        stopScreenCapture()
        
        // 清理媒体流
        localMediaStream?.dispose()
        localMediaStream = null
        
        // 清理视频源
        videoSource?.dispose()
        videoSource = null
        
        // 清理音频源
        audioSource?.dispose()
        audioSource = null
        
        // 关闭PeerConnection
        peerConnection?.close()
        peerConnection = null
        
        // 清理工厂
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        
        // 更新状态
        _connectionState.value = ConnectionState.CLOSED
        _isInitialized.value = false
        
        // 取消协程
        scope.cancel()
        
        Log.i(TAG, "WebRTC连接已关闭")
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