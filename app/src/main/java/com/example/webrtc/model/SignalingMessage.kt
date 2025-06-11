package com.example.webrtc.model

import com.google.gson.annotations.SerializedName

/**
 * 信令消息数据模型
 * 用于WebRTC信令通信
 */
data class SignalingMessage(
    @SerializedName("type")
    val type: MessageType,
    
    @SerializedName("data")
    val data: Any? = null,
    
    @SerializedName("roomId")
    val roomId: String? = null,
    
    @SerializedName("fromId")
    val fromId: String? = null,
    
    @SerializedName("toId")
    val toId: String? = null,
    
    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 消息类型枚举
 */
enum class MessageType {
    // 房间管理
    @SerializedName("join_room")
    JOIN_ROOM,
    
    @SerializedName("leave_room")
    LEAVE_ROOM,
    
    @SerializedName("room_joined")
    ROOM_JOINED,
    
    @SerializedName("room_left")
    ROOM_LEFT,
    
    @SerializedName("user_joined")
    USER_JOINED,
    
    @SerializedName("user_left")
    USER_LEFT,
    
    // WebRTC信令
    @SerializedName("offer")
    OFFER,
    
    @SerializedName("answer")
    ANSWER,
    
    @SerializedName("ice_candidate")
    ICE_CANDIDATE,
    
    // 控制消息
    @SerializedName("start_screen_share")
    START_SCREEN_SHARE,
    
    @SerializedName("stop_screen_share")
    STOP_SCREEN_SHARE,
    
    @SerializedName("error")
    ERROR,
    
    @SerializedName("ping")
    PING,
    
    @SerializedName("pong")
    PONG
}

/**
 * SDP消息数据
 */
data class SDPData(
    @SerializedName("type")
    val type: String, // "offer" or "answer"
    
    @SerializedName("sdp")
    val sdp: String
)

/**
 * ICE候选数据
 */
data class ICECandidateData(
    @SerializedName("candidate")
    val candidate: String,
    
    @SerializedName("sdpMid")
    val sdpMid: String,
    
    @SerializedName("sdpMLineIndex")
    val sdpMLineIndex: Int
)

/**
 * 房间信息
 */
data class RoomInfo(
    @SerializedName("roomId")
    val roomId: String,
    
    @SerializedName("users")
    val users: List<UserInfo> = emptyList(),
    
    @SerializedName("maxUsers")
    val maxUsers: Int = 2,
    
    @SerializedName("createdAt")
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 用户信息
 */
data class UserInfo(
    @SerializedName("userId")
    val userId: String,
    
    @SerializedName("username")
    val username: String,
    
    @SerializedName("role")
    val role: UserRole = UserRole.VIEWER,
    
    @SerializedName("joinedAt")
    val joinedAt: Long = System.currentTimeMillis()
)

/**
 * 用户角色枚举
 */
enum class UserRole {
    @SerializedName("broadcaster")
    BROADCASTER, // 投屏方
    
    @SerializedName("viewer")
    VIEWER       // 观看方
}

/**
 * 错误信息
 */
data class ErrorInfo(
    @SerializedName("code")
    val code: Int,
    
    @SerializedName("message")
    val message: String,
    
    @SerializedName("details")
    val details: String? = null
) 