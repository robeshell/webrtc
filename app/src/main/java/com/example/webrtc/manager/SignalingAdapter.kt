package com.example.webrtc.manager

/**
 * 信令管理器适配器
 * 将SocketIOSignalingManager的回调适配到现有的SignalingCallback接口
 */
class SignalingAdapter(private val callback: SignalingCallback) : 
    SocketIOSignalingManager.SignalingCallback {
    
    override fun onSignalingConnected() {
        callback.onSignalingConnected()
    }
    
    override fun onSignalingDisconnected() {
        callback.onSignalingDisconnected()
    }
    
    override fun onSignalingError(error: String) {
        callback.onSignalingError(error)
    }
    
    override fun onRoomJoined(roomId: String) {
        callback.onRoomJoined(roomId)
    }
    
    override fun onRoomLeft(roomId: String) {
        callback.onRoomLeft(roomId)
    }
    
    override fun onUserJoined(userId: String, userType: String) {
        callback.onUserJoined(userId)
    }
    
    override fun onUserLeft(userId: String) {
        callback.onUserLeft(userId)
    }
    
    override fun onOfferReceived(fromUserId: String, sdp: String) {
        callback.onOfferReceived(sdp, fromUserId)
    }
    
    override fun onAnswerReceived(fromUserId: String, sdp: String) {
        callback.onAnswerReceived(sdp, fromUserId)
    }
    
    override fun onIceCandidateReceived(fromUserId: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        callback.onIceCandidateReceived(candidate, sdpMid, sdpMLineIndex, fromUserId)
    }
} 