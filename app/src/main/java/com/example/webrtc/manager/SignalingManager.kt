package com.example.webrtc.manager

import android.util.Log
import com.example.webrtc.model.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import com.example.webrtc.config.WebRTCConfig

/**
 * 信令管理器
 * 负责WebSocket信令服务器连接和消息传递
 */
class SignalingManager(
    private val serverUrl: String,
    private val callback: SignalingCallback
) {
    
    companion object {
        private const val TAG = "SignalingManager"
    }
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // WebSocket客户端
    private var webSocketClient: WebSocketClient? = null
    private val gson = Gson()
    
    // 连接状态
    private val _signalingState = MutableStateFlow(SignalingState.DISCONNECTED)
    val signalingState: StateFlow<SignalingState> = _signalingState.asStateFlow()
    
    // 重连相关
    private val isReconnecting = AtomicBoolean(false)
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    
    // 心跳相关
    private var heartbeatJob: Job? = null
    
    // 当前房间信息
    private var currentRoomId: String? = null
    private var currentUserId: String? = null
    
    /**
     * 连接信令服务器
     */
    fun connect() {
        if (_signalingState.value == SignalingState.CONNECTED || 
            _signalingState.value == SignalingState.CONNECTING) {
            Log.w(TAG, "已连接或正在连接中")
            return
        }
        
        Log.d(TAG, "连接信令服务器: $serverUrl")
        _signalingState.value = SignalingState.CONNECTING
        
        try {
            val uri = URI.create(serverUrl)
            webSocketClient = object : WebSocketClient(uri) {
                
                override fun onOpen(handshake: ServerHandshake) {
                    Log.i(TAG, "WebSocket连接成功")
                    scope.launch {
                        _signalingState.value = SignalingState.CONNECTED
                        reconnectAttempts = 0
                        isReconnecting.set(false)
                        
                        // 启动心跳
                        startHeartbeat()
                        
                        callback.onSignalingConnected()
                    }
                }
                
                override fun onMessage(message: String) {
                    Log.d(TAG, "收到信令消息: $message")
                    scope.launch {
                        handleSignalingMessage(message)
                    }
                }
                
                override fun onClose(code: Int, reason: String, remote: Boolean) {
                    Log.w(TAG, "WebSocket连接关闭: code=$code, reason=$reason, remote=$remote")
                    scope.launch {
                        _signalingState.value = SignalingState.DISCONNECTED
                        stopHeartbeat()
                        
                        callback.onSignalingDisconnected()
                        
                        // 自动重连
                        if (remote && !isReconnecting.get()) {
                            startReconnect()
                        }
                    }
                }
                
                override fun onError(ex: Exception) {
                    Log.e(TAG, "WebSocket错误", ex)
                    scope.launch {
                        _signalingState.value = SignalingState.ERROR
                        callback.onSignalingError(ex.message ?: "WebSocket错误")
                    }
                }
            }
            
            webSocketClient?.connect()
            
        } catch (e: Exception) {
            Log.e(TAG, "连接信令服务器失败", e)
            _signalingState.value = SignalingState.ERROR
            callback.onSignalingError(e.message ?: "连接失败")
        }
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        Log.d(TAG, "断开信令服务器连接")
        
        // 停止重连
        isReconnecting.set(false)
        reconnectJob?.cancel()
        
        // 停止心跳
        stopHeartbeat()
        
        // 关闭WebSocket连接
        webSocketClient?.close()
        webSocketClient = null
        
        _signalingState.value = SignalingState.DISCONNECTED
        
        // 清理房间信息
        currentRoomId = null
        currentUserId = null
    }
    
    /**
     * 发送信令消息
     */
    fun sendMessage(message: SignalingMessage) {
        if (_signalingState.value != SignalingState.CONNECTED) {
            Log.w(TAG, "信令服务器未连接，无法发送消息")
            return
        }
        
        try {
            val jsonMessage = gson.toJson(message)
            webSocketClient?.send(jsonMessage)
            Log.d(TAG, "发送信令消息: $jsonMessage")
        } catch (e: Exception) {
            Log.e(TAG, "发送信令消息失败", e)
            callback.onSignalingError("发送消息失败: ${e.message}")
        }
    }
    
    /**
     * 加入房间
     */
    fun joinRoom(roomId: String, userId: String, role: UserRole = UserRole.BROADCASTER) {
        Log.d(TAG, "加入房间: roomId=$roomId, userId=$userId, role=$role")
        
        currentRoomId = roomId
        currentUserId = userId
        
        val message = SignalingMessage(
            type = MessageType.JOIN_ROOM,
            data = mapOf(
                "roomId" to roomId,
                "userId" to userId,
                "role" to role.name,
                "userAgent" to "Android"
            ),
            roomId = roomId,
            fromId = userId
        )
        
        sendMessage(message)
    }
    
    /**
     * 离开房间
     */
    fun leaveRoom() {
        val roomId = currentRoomId
        val userId = currentUserId
        
        if (roomId != null && userId != null) {
            Log.d(TAG, "离开房间: roomId=$roomId, userId=$userId")
            
            val message = SignalingMessage(
                type = MessageType.LEAVE_ROOM,
                data = mapOf(
                    "roomId" to roomId,
                    "userId" to userId
                ),
                roomId = roomId,
                fromId = userId
            )
            
            sendMessage(message)
        }
        
        currentRoomId = null
        currentUserId = null
    }
    
    /**
     * 发送Offer
     */
    fun sendOffer(sdp: String, toUserId: String) {
        val message = SignalingMessage(
            type = MessageType.OFFER,
            data = SDPData(type = "offer", sdp = sdp),
            roomId = currentRoomId,
            fromId = currentUserId,
            toId = toUserId
        )
        sendMessage(message)
    }
    
    /**
     * 发送Answer
     */
    fun sendAnswer(sdp: String, toUserId: String) {
        val message = SignalingMessage(
            type = MessageType.ANSWER,
            data = SDPData(type = "answer", sdp = sdp),
            roomId = currentRoomId,
            fromId = currentUserId,
            toId = toUserId
        )
        sendMessage(message)
    }
    
    /**
     * 发送ICE候选
     */
    fun sendIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int, toUserId: String) {
        val message = SignalingMessage(
            type = MessageType.ICE_CANDIDATE,
            data = ICECandidateData(
                candidate = candidate,
                sdpMid = sdpMid,
                sdpMLineIndex = sdpMLineIndex
            ),
            roomId = currentRoomId,
            fromId = currentUserId,
            toId = toUserId
        )
        sendMessage(message)
    }
    
    /**
     * 处理信令消息
     */
    private suspend fun handleSignalingMessage(message: String) {
        try {
            val signalingMessage = gson.fromJson(message, SignalingMessage::class.java)
            
            when (signalingMessage.type) {
                MessageType.ROOM_JOINED -> {
                    Log.i(TAG, "房间加入成功")
                    callback.onRoomJoined(signalingMessage.roomId ?: "")
                }
                
                MessageType.ROOM_LEFT -> {
                    Log.i(TAG, "房间离开成功")
                    callback.onRoomLeft(signalingMessage.roomId ?: "")
                }
                
                MessageType.USER_JOINED -> {
                    Log.i(TAG, "用户加入房间: ${signalingMessage.fromId}")
                    callback.onUserJoined(signalingMessage.fromId ?: "")
                }
                
                MessageType.USER_LEFT -> {
                    Log.i(TAG, "用户离开房间: ${signalingMessage.fromId}")
                    callback.onUserLeft(signalingMessage.fromId ?: "")
                }
                
                MessageType.OFFER -> {
                    Log.d(TAG, "收到Offer")
                    val sdpData = gson.fromJson(gson.toJson(signalingMessage.data), SDPData::class.java)
                    callback.onOfferReceived(sdpData.sdp, signalingMessage.fromId ?: "")
                }
                
                MessageType.ANSWER -> {
                    Log.d(TAG, "收到Answer")
                    val sdpData = gson.fromJson(gson.toJson(signalingMessage.data), SDPData::class.java)
                    callback.onAnswerReceived(sdpData.sdp, signalingMessage.fromId ?: "")
                }
                
                MessageType.ICE_CANDIDATE -> {
                    Log.d(TAG, "收到ICE候选")
                    val iceData = gson.fromJson(gson.toJson(signalingMessage.data), ICECandidateData::class.java)
                    callback.onIceCandidateReceived(
                        iceData.candidate,
                        iceData.sdpMid,
                        iceData.sdpMLineIndex,
                        signalingMessage.fromId ?: ""
                    )
                }
                
                MessageType.ERROR -> {
                    Log.e(TAG, "收到错误消息")
                    val errorInfo = gson.fromJson(gson.toJson(signalingMessage.data), ErrorInfo::class.java)
                    callback.onSignalingError("服务器错误: ${errorInfo.message}")
                }
                
                MessageType.PONG -> {
                    Log.v(TAG, "收到Pong消息")
                }
                
                else -> {
                    Log.w(TAG, "未知消息类型: ${signalingMessage.type}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理信令消息失败", e)
            callback.onSignalingError("消息解析失败: ${e.message}")
        }
    }
    
    /**
     * 启动心跳
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        
        heartbeatJob = scope.launch {
            while (isActive && _signalingState.value == SignalingState.CONNECTED) {
                try {
                    val pingMessage = SignalingMessage(
                        type = MessageType.PING,
                        roomId = currentRoomId,
                        fromId = currentUserId
                    )
                    sendMessage(pingMessage)
                    delay(WebRTCConfig.Signaling.HEARTBEAT_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "心跳失败", e)
                    break
                }
            }
        }
    }
    
    /**
     * 停止心跳
     */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
    
    /**
     * 开始重连
     */
    private fun startReconnect() {
        if (isReconnecting.get() || reconnectAttempts >= WebRTCConfig.Signaling.MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "达到最大重连次数或正在重连中")
            return
        }
        
        isReconnecting.set(true)
        reconnectJob = scope.launch {
            while (isActive && 
                   _signalingState.value != SignalingState.CONNECTED && 
                   reconnectAttempts < WebRTCConfig.Signaling.MAX_RECONNECT_ATTEMPTS) {
                
                reconnectAttempts++
                Log.i(TAG, "尝试重连 ($reconnectAttempts/${WebRTCConfig.Signaling.MAX_RECONNECT_ATTEMPTS})")
                
                _signalingState.value = SignalingState.RECONNECTING
                
                delay(WebRTCConfig.Signaling.RECONNECT_INTERVAL)
                
                try {
                    webSocketClient?.close()
                    connect()
                    
                    // 等待连接结果
                    delay(WebRTCConfig.Signaling.CONNECTION_TIMEOUT)
                    
                    if (_signalingState.value == SignalingState.CONNECTED) {
                        Log.i(TAG, "重连成功")
                        break
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "重连失败", e)
                }
            }
            
            if (_signalingState.value != SignalingState.CONNECTED) {
                Log.e(TAG, "重连失败，达到最大重连次数")
                _signalingState.value = SignalingState.ERROR
                callback.onSignalingError("重连失败")
            }
            
            isReconnecting.set(false)
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        Log.d(TAG, "释放信令管理器资源")
        
        // 离开房间
        leaveRoom()
        
        // 断开连接
        disconnect()
        
        // 取消协程
        scope.cancel()
        
        Log.i(TAG, "信令管理器资源释放完成")
    }
    
    /**
     * 获取当前房间ID
     */
    fun getCurrentRoomId(): String? = currentRoomId
    
    /**
     * 获取当前用户ID
     */
    fun getCurrentUserId(): String? = currentUserId
    
    /**
     * 检查是否连接
     */
    fun isConnected(): Boolean = _signalingState.value == SignalingState.CONNECTED
}

/**
 * 信令回调接口
 */
interface SignalingCallback {
    fun onSignalingConnected()
    fun onSignalingDisconnected()
    fun onSignalingError(error: String)
    
    fun onRoomJoined(roomId: String)
    fun onRoomLeft(roomId: String)
    fun onUserJoined(userId: String)
    fun onUserLeft(userId: String)
    
    fun onOfferReceived(sdp: String, fromUserId: String)
    fun onAnswerReceived(sdp: String, fromUserId: String)
    fun onIceCandidateReceived(candidate: String, sdpMid: String, sdpMLineIndex: Int, fromUserId: String)
} 