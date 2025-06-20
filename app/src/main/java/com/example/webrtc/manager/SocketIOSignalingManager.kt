package com.example.webrtc.manager

import android.util.Log
import com.example.webrtc.model.SignalingMessage
import com.example.webrtc.model.MessageType
import com.example.webrtc.model.UserRole
import com.example.webrtc.model.SignalingState
import com.example.webrtc.model.SDPData
import com.example.webrtc.model.ICECandidateData
import com.google.gson.Gson
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Socket.IO信令管理器
 * 使用Socket.IO协议与服务器通信
 */
class SocketIOSignalingManager(
    private val callback: SignalingCallback
) {
    companion object {
        private const val TAG = "SocketIOSignalingManager" 
        private const val CONNECTION_TIMEOUT = 10000L
        private const val HEARTBEAT_INTERVAL = 30000L
        private const val RECONNECT_INTERVAL = 5000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }
    
    interface SignalingCallback {
        fun onSignalingConnected()
        fun onSignalingDisconnected()
        fun onSignalingError(error: String)
        fun onRoomJoined(roomId: String)
        fun onRoomLeft(roomId: String)
        fun onUserJoined(userId: String, userType: String)
        fun onUserLeft(userId: String)
        fun onOfferReceived(fromUserId: String, sdp: String)
        fun onAnswerReceived(fromUserId: String, sdp: String)
        fun onIceCandidateReceived(fromUserId: String, candidate: String, sdpMid: String, sdpMLineIndex: Int)
    }
    
    // Socket.IO 客户端
    private var socket: Socket? = null
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 状态管理
    private val _signalingState = MutableStateFlow(SignalingState.DISCONNECTED)
    val signalingState: StateFlow<SignalingState> = _signalingState.asStateFlow()
    
    // 重连管理
    private val reconnectAttempts = AtomicInteger(0)
    private val isReconnecting = AtomicBoolean(false)
    private var reconnectJob: Job? = null
    
    // 心跳管理
    private var heartbeatJob: Job? = null
    
    // 房间信息
    private var currentRoomId: String? = null
    private var currentUserId: String? = null
    
    /**
     * 测试网络连接
     */
    private fun testNetworkConnection(serverUrl: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("$serverUrl/socket.io/?EIO=4&transport=polling&t=${System.currentTimeMillis()}")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"
                
                val responseCode = connection.responseCode
                
                if (responseCode != 200) {
                    Log.e(TAG, "网络测试失败 - HTTP $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "网络测试异常", e)
            }
        }
    }

    /**
     * 连接信令服务器
     */
    fun connect(serverUrl: String) {
        if (_signalingState.value == SignalingState.CONNECTING || 
            _signalingState.value == SignalingState.CONNECTED) {
            Log.w(TAG, "已经连接或正在连接中")
            return
        }
        
        // 先测试网络连接
        testNetworkConnection(serverUrl)
        
        try {
            _signalingState.value = SignalingState.CONNECTING
            
            // 配置Socket.IO选项
            val options = IO.Options().apply {
                timeout = CONNECTION_TIMEOUT
                reconnection = false // 手动控制重连
                forceNew = true
                // 优先使用polling，避免websocket连接问题
                transports = arrayOf("polling")
                // 禁用升级到websocket
                upgrade = false
            }
            
            // 创建Socket.IO连接
            socket = IO.socket(URI.create(serverUrl), options)
            
            // 设置事件监听器
            setupSocketListeners()
            
            // 连接
            socket?.connect()
            
        } catch (e: Exception) {
            Log.e(TAG, "连接Socket.IO服务器失败", e)
            _signalingState.value = SignalingState.ERROR
            callback.onSignalingError(e.message ?: "连接失败")
        }
    }
    
    /**
     * 设置Socket.IO事件监听器
     */
    private fun setupSocketListeners() {
        socket?.apply {
            // 连接成功
            on(Socket.EVENT_CONNECT) {
                Log.i(TAG, "Socket.IO连接成功")
                scope.launch {
                    _signalingState.value = SignalingState.CONNECTED
                    reconnectAttempts.set(0)
                    isReconnecting.set(false)
                    startHeartbeat()
                    callback.onSignalingConnected()
                }
            }
            
            // 连接断开
            on(Socket.EVENT_DISCONNECT) { args ->
                val reason = if (args.isNotEmpty()) args[0].toString() else "未知原因"
                Log.w(TAG, "Socket.IO连接断开: $reason")
                scope.launch {
                    _signalingState.value = SignalingState.DISCONNECTED
                    stopHeartbeat()
                    callback.onSignalingDisconnected()
                    
                    // 如果不是主动断开，尝试重连
                    if (reason != "io client disconnect" && !isReconnecting.get()) {
                        startReconnect()
                    }
                }
            }
            
            // 连接错误
            on(Socket.EVENT_CONNECT_ERROR) { args ->
                val error = if (args.isNotEmpty()) args[0].toString() else "连接错误"
                Log.e(TAG, "Socket.IO连接错误: $error")
                scope.launch {
                    _signalingState.value = SignalingState.ERROR
                    callback.onSignalingError(error)
                }
            }
            
            // 房间加入成功
            on("room_joined") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as org.json.JSONObject
                        val roomId = data.getString("roomId")
                        Log.i(TAG, "房间加入成功: $roomId")
                        scope.launch {
                            callback.onRoomJoined(roomId)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析room_joined消息失败", e)
                    }
                }
            }
            
            // 房间离开成功
            on("room_left") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as org.json.JSONObject
                        val roomId = data.getString("roomId")
                        Log.i(TAG, "房间离开成功: $roomId")
                        scope.launch {
                            callback.onRoomLeft(roomId)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析room_left消息失败", e)
                    }
                }
            }
            
            // 用户加入
            on("user_joined") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as org.json.JSONObject
                        val userId = data.getString("userId")
                        val userType = data.optString("userType", "viewer")
                        Log.i(TAG, "用户加入: $userId, 类型: $userType")
                        scope.launch {
                            callback.onUserJoined(userId, userType)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析user_joined消息失败", e)
                    }
                }
            }
            
            // 用户离开
            on("user_left") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as org.json.JSONObject
                        val userId = data.getString("userId")
                        Log.i(TAG, "用户离开: $userId")
                        scope.launch {
                            callback.onUserLeft(userId)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析user_left消息失败", e)
                    }
                }
            }
            
            // WebRTC Offer
            on("offer") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as org.json.JSONObject
                        val fromUserId = data.getString("fromUserId")
                        val sdp = data.getString("sdp")
                        Log.d(TAG, "收到Offer: from=$fromUserId")
                        scope.launch {
                            callback.onOfferReceived(fromUserId, sdp)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析offer消息失败", e)
                    }
                }
            }
            
            // WebRTC Answer
            on("answer") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as org.json.JSONObject
                        val fromUserId = data.getString("fromUserId")
                        val sdp = data.getString("sdp")
                        Log.d(TAG, "收到Answer: from=$fromUserId")
                        scope.launch {
                            callback.onAnswerReceived(fromUserId, sdp)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析answer消息失败", e)
                    }
                }
            }
            
            // ICE Candidate
            on("ice_candidate") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as org.json.JSONObject
                        val fromUserId = data.getString("fromUserId")
                        val candidate = data.getString("candidate")
                        val sdpMid = data.getString("sdpMid")
                        val sdpMLineIndex = data.getInt("sdpMLineIndex")
                        Log.d(TAG, "收到ICE Candidate: from=$fromUserId")
                        scope.launch {
                            callback.onIceCandidateReceived(fromUserId, candidate, sdpMid, sdpMLineIndex)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析ice_candidate消息失败", e)
                    }
                }
            }
            
            // 错误消息
            on("error") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as org.json.JSONObject
                        val message = data.getString("message")
                        Log.e(TAG, "服务器错误: $message")
                        scope.launch {
                            callback.onSignalingError(message)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析error消息失败", e)
                    }
                }
            }
            
            // Pong响应
            on("pong") {
                Log.d(TAG, "收到心跳响应")
            }
        }
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        Log.d(TAG, "断开Socket.IO连接")
        
        // 停止重连
        isReconnecting.set(false)
        reconnectJob?.cancel()
        
        // 停止心跳
        stopHeartbeat()
        
        // 关闭Socket连接
        socket?.disconnect()
        socket?.off() // 移除所有监听器
        socket = null
        
        _signalingState.value = SignalingState.DISCONNECTED
        
        // 清理房间信息
        currentRoomId = null
        currentUserId = null
    }
    
    /**
     * 加入房间
     */
    fun joinRoom(roomId: String, userId: String, userType: String = "broadcaster") {
        if (_signalingState.value != SignalingState.CONNECTED) {
            Log.w(TAG, "Socket.IO未连接，无法加入房间")
            return
        }
        
        Log.d(TAG, "加入房间: roomId=$roomId, userId=$userId, userType=$userType")
        
        currentRoomId = roomId
        currentUserId = userId
        
        try {
            val data = org.json.JSONObject().apply {
                put("roomId", roomId)
                put("userId", userId)
                put("userType", userType)
            }
            
            socket?.emit("join_room", data)
        } catch (e: Exception) {
            Log.e(TAG, "加入房间失败", e)
            callback.onSignalingError("加入房间失败: ${e.message}")
        }
    }
    
    /**
     * 离开房间
     */
    fun leaveRoom() {
        val roomId = currentRoomId
        val userId = currentUserId
        
        if (roomId != null && userId != null) {
            Log.d(TAG, "离开房间: roomId=$roomId, userId=$userId")
            
            try {
                val data = org.json.JSONObject().apply {
                    put("roomId", roomId)
                    put("userId", userId)
                }
                
                socket?.emit("leave_room", data)
            } catch (e: Exception) {
                Log.e(TAG, "离开房间失败", e)
            }
        }
        
        currentRoomId = null
        currentUserId = null
    }
    
    /**
     * 发送Offer
     */
    fun sendOffer(sdp: String, toUserId: String) {
        try {
            val data = org.json.JSONObject().apply {
                put("targetUserId", toUserId)
                put("sdp", sdp)
            }
            
            socket?.emit("offer", data)
            Log.d(TAG, "发送Offer到: $toUserId")
        } catch (e: Exception) {
            Log.e(TAG, "发送Offer失败", e)
            callback.onSignalingError("发送Offer失败: ${e.message}")
        }
    }
    
    /**
     * 发送Answer
     */
    fun sendAnswer(sdp: String, toUserId: String) {
        try {
            val data = org.json.JSONObject().apply {
                put("targetUserId", toUserId)
                put("sdp", sdp)
            }
            
            socket?.emit("answer", data)
            Log.d(TAG, "发送Answer到: $toUserId")
        } catch (e: Exception) {
            Log.e(TAG, "发送Answer失败", e)
            callback.onSignalingError("发送Answer失败: ${e.message}")
        }
    }
    
    /**
     * 发送ICE候选
     */
    fun sendIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int, toUserId: String) {
        try {
            val data = org.json.JSONObject().apply {
                put("targetUserId", toUserId)
                put("candidate", candidate)
                put("sdpMid", sdpMid)
                put("sdpMLineIndex", sdpMLineIndex)
            }
            
            socket?.emit("ice_candidate", data)
            Log.d(TAG, "发送ICE Candidate到: $toUserId")
        } catch (e: Exception) {
            Log.e(TAG, "发送ICE Candidate失败", e)
            callback.onSignalingError("发送ICE Candidate失败: ${e.message}")
        }
    }
    
    /**
     * 开始心跳
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _signalingState.value == SignalingState.CONNECTED) {
                try {
                    socket?.emit("ping")
                    delay(HEARTBEAT_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "心跳发送失败", e)
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
        if (isReconnecting.get() || reconnectAttempts.get() >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "已在重连中或达到最大重连次数")
            return
        }
        
        isReconnecting.set(true)
        val attempt = reconnectAttempts.incrementAndGet()
        
        Log.i(TAG, "尝试重连 ($attempt/$MAX_RECONNECT_ATTEMPTS)")
        
        reconnectJob = scope.launch {
            delay(RECONNECT_INTERVAL * attempt) // 递增延迟
            
            if (isActive && isReconnecting.get()) {
                socket?.connect()
            }
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        disconnect()
        scope.cancel()
    }
} 