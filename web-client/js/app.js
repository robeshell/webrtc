/**
 * 主应用程序类
 * 协调 WebRTC 客户端、UI 控制器和 Socket.IO 信令通信
 */
class App {
    constructor() {
        // 核心组件
        this.uiController = null;
        this.webrtcClient = null;
        this.socket = null;
        
        // 连接状态
        this.isSocketConnected = false;
        this.isInRoom = false;
        this.currentRoomId = '';
        this.currentUserId = '';
        this.serverUrl = '';
        
        // 重连机制
        this.reconnectAttempts = 0;
        this.maxReconnectAttempts = 5;
        this.reconnectDelay = 2000;
        this.reconnectTimer = null;
        
        // 心跳机制
        this.heartbeatInterval = null;
        this.heartbeatTimer = 30000; // 30秒
        
        this.initialize();
    }

    /**
     * 初始化应用程序
     */
    async initialize() {
        console.log('初始化应用程序...');
        
        try {
            // 初始化UI控制器
            this.uiController = new UIController();
            
            // 初始化WebRTC客户端
            this.webrtcClient = new WebRTCClient();
            
            // 设置WebRTC回调
            this.setupWebRTCCallbacks();
            
            // 将实例暴露到全局作用域供其他组件使用
            window.app = this;
            window.uiController = this.uiController;
            window.webrtcClient = this.webrtcClient;
            
            // 🆕 自动填充连接信息或启动快速等待投屏
            this.autoFillConnectionInfo();
            
            console.log('应用程序初始化完成');
            this.uiController.addLog('应用程序初始化完成', 'success');
            
        } catch (error) {
            console.error('应用程序初始化失败:', error);
            this.uiController?.showError('应用程序初始化失败');
        }
    }

    /**
     * 设置WebRTC回调函数
     */
    setupWebRTCCallbacks() {
        this.webrtcClient.setCallbacks({
            onIceCandidate: (candidate) => {
                this.sendIceCandidate(candidate);
            },
            onConnectionStateChange: (state) => {
                this.handleWebRTCStateChange(state);
            },
            onTrackReceived: (stream) => {
                this.handleTrackReceived(stream);
            },
            onError: (error) => {
                this.handleWebRTCError(error);
            }
        });
    }

    /**
     * 连接到信令服务器
     */
    async connect(roomId, userId, serverUrl) {
        console.log('开始连接...', { roomId, userId, serverUrl });
        
        // 验证参数
        if (!roomId || !userId || !serverUrl) {
            this.uiController.showError('请填写所有必需的连接信息');
            return;
        }

        // 保存连接信息
        this.currentRoomId = roomId;
        this.currentUserId = userId;
        this.serverUrl = serverUrl;

        // 更新UI状态
        this.uiController.updateButtonStates(false, false);
        this.uiController.updateServerStatus('connecting');
        this.uiController.addLog(`正在连接到服务器: ${serverUrl}`, 'info');

        try {
            // 断开现有连接
            if (this.socket) {
                this.disconnect();
            }

            // 创建Socket.IO连接
            await this.createSocketConnection();
            
        } catch (error) {
            console.error('连接失败:', error);
            this.uiController.showError('连接服务器失败');
            this.uiController.updateButtonStates(false, true);
            this.uiController.updateServerStatus('disconnected');
        }
    }

    /**
     * 创建Socket.IO连接
     */
    async createSocketConnection() {
        return new Promise((resolve, reject) => {
            try {
                // 解析服务器URL
                const url = this.parseServerUrl(this.serverUrl);
                console.log('连接到:', url);

                // 创建Socket.IO实例
                this.socket = io(url, {
                    transports: ['websocket', 'polling'],
                    autoConnect: true,
                    reconnection: false, // 我们自己处理重连
                    timeout: 10000
                });

                // 设置事件监听器
                this.setupSocketEventHandlers(resolve, reject);
                
            } catch (error) {
                console.error('创建Socket连接失败:', error);
                reject(error);
            }
        });
    }

    /**
     * 解析服务器URL
     */
    parseServerUrl(url) {
        // 简单的URL处理，确保格式正确
        if (!url.startsWith('http://') && !url.startsWith('https://') && !url.startsWith('ws://') && !url.startsWith('wss://')) {
            if (url.startsWith('localhost') || url.startsWith('127.0.0.1')) {
                return `http://${url}`;
            }
            return `https://${url}`;
        }
        
        // 将ws://转换为http://，wss://转换为https://
        if (url.startsWith('ws://')) {
            return url.replace('ws://', 'http://');
        }
        if (url.startsWith('wss://')) {
            return url.replace('wss://', 'https://');
        }
        
        return url;
    }

    /**
     * 设置Socket事件处理器
     */
    setupSocketEventHandlers(connectResolve, connectReject) {
        // 连接成功
        this.socket.on('connect', () => {
            console.log('Socket连接成功');
            this.isSocketConnected = true;
            this.reconnectAttempts = 0;
            
            this.uiController.updateServerStatus('connected');
            this.uiController.addLog('服务器连接成功', 'success');
            
            // 开始心跳
            this.startHeartbeat();
            
            // 加入房间
            this.joinRoom();
            
            connectResolve();
        });

        // 连接失败
        this.socket.on('connect_error', (error) => {
            console.error('Socket连接失败:', error);
            this.isSocketConnected = false;
            
            this.uiController.updateServerStatus('disconnected');
            this.uiController.showError('服务器连接失败');
            
            connectReject(error);
        });

        // 断开连接
        this.socket.on('disconnect', (reason) => {
            console.log('Socket连接断开:', reason);
            this.isSocketConnected = false;
            this.isInRoom = false;
            
            this.uiController.updateServerStatus('disconnected');
            this.uiController.updateRoomStatus('未加入');
            this.uiController.updateButtonStates(false, true);
            this.uiController.addLog(`连接断开: ${reason}`, 'warning');
            
            this.stopHeartbeat();
            
            // 自动重连
            if (reason !== 'io client disconnect') {
                this.attemptReconnect();
            }
        });

        // 房间相关事件
        this.socket.on('room_joined', (data) => {
            this.handleRoomJoined(data);
        });

        this.socket.on('room_left', (data) => {
            this.handleRoomLeft(data);
        });

        this.socket.on('user_joined', (data) => {
            this.handleUserJoined(data);
        });

        this.socket.on('user_left', (data) => {
            this.handleUserLeft(data);
        });

        // WebRTC信令事件
        this.socket.on('offer', (data) => {
            this.handleOffer(data);
        });

        this.socket.on('answer', (data) => {
            this.handleAnswer(data);
        });

        this.socket.on('ice_candidate', (data) => {
            this.handleIceCandidate(data);
        });

        // 错误处理
        this.socket.on('error', (data) => {
            console.error('Socket错误:', data);
            this.uiController.showError(data.message || '服务器错误');
        });

        // 心跳响应
        this.socket.on('pong', (data) => {
            console.log('收到心跳响应:', data);
        });
    }

    /**
     * 加入房间
     */
    joinRoom() {
        if (!this.socket || !this.isSocketConnected) {
            console.error('Socket未连接，无法加入房间');
            return;
        }

        console.log('加入房间:', this.currentRoomId);
        
        this.socket.emit('join_room', {
            roomId: this.currentRoomId,
            userId: this.currentUserId,
            userType: 'viewer' // Web客户端作为观看者
        });

        this.uiController.addLog(`正在加入房间: ${this.currentRoomId}`, 'info');
    }

    /**
     * 处理房间加入成功
     */
    handleRoomJoined(data) {
        console.log('成功加入房间:', data);
        this.isInRoom = true;
        
        this.uiController.updateRoomStatus(`${data.roomId} (${data.userType})`);
        this.uiController.updateButtonStates(true, false);
        this.uiController.updateRoomInfo(data.roomId, data.roomInfo?.userCount || 0);
        this.uiController.addLog(`成功加入房间: ${data.roomId}`, 'success');
        this.uiController.showSuccess('已连接到房间，等待屏幕投屏...');
    }

    /**
     * 处理房间离开
     */
    handleRoomLeft(data) {
        console.log('离开房间:', data);
        this.isInRoom = false;
        
        this.uiController.updateRoomStatus('未加入');
        this.uiController.updateRoomInfo('', 0);
        this.uiController.addLog(`离开房间: ${data.roomId}`, 'info');
    }

    /**
     * 处理用户加入
     */
    handleUserJoined(data) {
        console.log('用户加入:', data);
        this.uiController.addLog(`用户 ${data.userId} 加入房间 (${data.userType})`, 'info');
        
        // 如果是屏幕投屏者加入，作为viewer主动发起连接
        if (data.userType === 'broadcaster') {
            this.uiController.addLog('检测到投屏设备，准备建立连接...', 'info');
            setTimeout(() => {
                this.initiateWebRTCConnection();
            }, 1000);
        }
    }

    /**
     * 处理用户离开
     */
    handleUserLeft(data) {
        console.log('用户离开:', data);
        this.uiController.addLog(`用户 ${data.userId} 离开房间`, 'warning');
        
        // 如果是投屏者离开，断开WebRTC连接
        this.webrtcClient.closePeerConnection();
    }

    /**
     * 主动发起WebRTC连接（作为Viewer）
     */
    async initiateWebRTCConnection() {
        try {
            console.log('作为Viewer发起WebRTC连接...');
            this.uiController.updateConnectionStatus('正在建立连接');
            
            const offer = await this.webrtcClient.createOffer();
            
            // 发送Offer给投屏者
            this.socket.emit('offer', {
                targetUserId: 'broadcaster', // 发送给投屏方
                sdp: offer.sdp,              // 只发送SDP字符串，不是整个offer对象
                timestamp: new Date()
            });
            
            this.uiController.addLog('已发送连接请求到投屏设备', 'info');
            
        } catch (error) {
            console.error('发起WebRTC连接失败:', error);
            this.uiController.showError('建立连接失败');
            this.uiController.updateConnectionStatus('连接失败');
        }
    }

    /**
     * 处理收到的Offer
     */
    async handleOffer(data) {
        try {
            console.log('收到Offer:', data);
            this.uiController.addLog('收到投屏设备的连接请求', 'info');
            this.uiController.updateConnectionStatus('建立连接中');
            
            const answer = await this.webrtcClient.createAnswer(data.sdp);
            
            // 发送Answer
            this.socket.emit('answer', {
                targetUserId: 'broadcaster', // 发送给投屏方
                sdp: answer.sdp,              // 只发送SDP字符串，不是整个answer对象
                timestamp: new Date()
            });
            
            this.uiController.addLog('已发送连接响应', 'info');
            
        } catch (error) {
            console.error('处理Offer失败:', error);
            this.uiController.showError('处理连接请求失败');
        }
    }

    /**
     * 处理收到的Answer
     */
    async handleAnswer(data) {
        try {
            console.log('收到Answer:', data);
            this.uiController.addLog('收到投屏设备的连接响应', 'info');
            
            await this.webrtcClient.setRemoteAnswer(data.sdp);
            
        } catch (error) {
            console.error('处理Answer失败:', error);
            this.uiController.showError('处理连接响应失败');
        }
    }

    /**
     * 处理收到的ICE Candidate
     */
    async handleIceCandidate(data) {
        try {
            console.log('收到ICE Candidate:', data);
            
            await this.webrtcClient.addIceCandidate({
                candidate: data.candidate,
                sdpMid: data.sdpMid,
                sdpMLineIndex: data.sdpMLineIndex
            });
            
        } catch (error) {
            console.error('处理ICE Candidate失败:', error);
        }
    }

    /**
     * 发送ICE Candidate
     */
    sendIceCandidate(candidate) {
        if (!this.socket || !this.isSocketConnected) {
            console.warn('Socket未连接，无法发送ICE Candidate');
            return;
        }

        console.log('发送ICE Candidate:', candidate);
        
        this.socket.emit('ice_candidate', {
            targetUserId: 'broadcaster', // 发送给投屏方
            candidate: candidate.candidate,
            sdpMid: candidate.sdpMid,
            sdpMLineIndex: candidate.sdpMLineIndex,
            timestamp: new Date()
        });
    }

    /**
     * 处理WebRTC状态变化
     */
    handleWebRTCStateChange(state) {
        console.log('WebRTC状态变化:', state);
        
        switch (state) {
            case 'connecting':
                this.uiController.updateConnectionStatus('连接中');
                break;
            case 'connected':
                this.uiController.updateConnectionStatus('已连接');
                this.uiController.showSuccess('屏幕投屏连接成功！');
                break;
            case 'disconnected':
                this.uiController.updateConnectionStatus('已断开');
                break;
            case 'failed':
                this.uiController.updateConnectionStatus('连接失败');
                break;
            case 'closed':
                this.uiController.updateConnectionStatus('已关闭');
                break;
        }
    }

    /**
     * 处理收到媒体流
     */
    handleTrackReceived(stream) {
        console.log('收到媒体流:', stream);
        this.uiController.addLog('开始接收屏幕投屏画面', 'success');
    }

    /**
     * 处理WebRTC错误
     */
    handleWebRTCError(error) {
        console.error('WebRTC错误:', error);
        
        if (error === 'connection_failed') {
            this.uiController.showError('WebRTC连接失败，正在尝试重新连接...');
            // 可以在这里实现WebRTC重连逻辑
        }
    }

    /**
     * 断开连接
     */
    disconnect() {
        console.log('断开连接...');
        
        // 停止心跳
        this.stopHeartbeat();
        
        // 停止重连
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
        
        // 关闭WebRTC连接
        if (this.webrtcClient) {
            this.webrtcClient.closePeerConnection();
        }
        
        // 断开Socket连接
        if (this.socket) {
            this.socket.disconnect();
            this.socket = null;
        }
        
        // 重置状态
        this.isSocketConnected = false;
        this.isInRoom = false;
        this.reconnectAttempts = 0;
        
        // 更新UI
        this.uiController.reset();
        this.uiController.addLog('连接已断开', 'info');
    }

    /**
     * 开始心跳
     */
    startHeartbeat() {
        this.stopHeartbeat();
        
        this.heartbeatInterval = setInterval(() => {
            if (this.socket && this.isSocketConnected) {
                this.socket.emit('ping');
            }
        }, this.heartbeatTimer);
    }

    /**
     * 停止心跳
     */
    stopHeartbeat() {
        if (this.heartbeatInterval) {
            clearInterval(this.heartbeatInterval);
            this.heartbeatInterval = null;
        }
    }

    /**
     * 尝试重连
     */
    attemptReconnect() {
        if (this.reconnectAttempts >= this.maxReconnectAttempts) {
            console.log('达到最大重连次数，停止重连');
            this.uiController.showError('无法连接到服务器，请检查网络连接');
            return;
        }

        this.reconnectAttempts++;
        console.log(`尝试重连 (${this.reconnectAttempts}/${this.maxReconnectAttempts})...`);
        
        this.uiController.addLog(`尝试重连 (${this.reconnectAttempts}/${this.maxReconnectAttempts})`, 'warning');
        this.uiController.updateServerStatus('connecting');

        this.reconnectTimer = setTimeout(() => {
            this.connect(this.currentRoomId, this.currentUserId, this.serverUrl);
        }, this.reconnectDelay * this.reconnectAttempts);
    }

    /**
     * 获取当前状态
     */
    getStatus() {
        return {
            isSocketConnected: this.isSocketConnected,
            isInRoom: this.isInRoom,
            currentRoomId: this.currentRoomId,
            currentUserId: this.currentUserId,
            reconnectAttempts: this.reconnectAttempts,
            webrtcConnected: this.webrtcClient?.isConnected || false
        };
    }

    /**
     * 🆕 自动填充连接信息
     */
    autoFillConnectionInfo() {
        // 从URL参数获取房间号
        const urlParams = new URLSearchParams(window.location.search);
        const roomId = urlParams.get('room') || urlParams.get('roomId');
        const serverUrl = urlParams.get('server') || urlParams.get('serverUrl');
        
        if (roomId) {
            const roomInput = document.getElementById('roomIdInput');
            if (roomInput) {
                roomInput.value = roomId;
                console.log(`🏠 自动填入房间号: ${roomId}`);
                this.uiController?.showSuccess(`已自动填入房间号: ${roomId}`);
            }
        }
        
        if (serverUrl) {
            const serverInput = document.getElementById('serverUrlInput');
            if (serverInput) {
                // 确保URL格式正确
                const formattedUrl = serverUrl.startsWith('ws://') || serverUrl.startsWith('wss://') 
                    ? serverUrl 
                    : `ws://${serverUrl}`;
                serverInput.value = formattedUrl;
                console.log(`🌐 自动填入服务器地址: ${formattedUrl}`);
            }
        }
        
        // 生成默认用户名
        const userNameInput = document.getElementById('userNameInput');
        if (userNameInput && !userNameInput.value) {
            userNameInput.value = `用户_${Math.floor(Math.random() * 1000)}`;
        }
        
        // 🚀 如果有URL参数房间号，自动尝试连接
        if (roomId) {
            // 延迟1秒后自动连接，给用户时间看到界面
            setTimeout(() => {
                this.autoConnect();
            }, 1000);
        } else {
            // 🆕 没有URL参数时，自动启动快速等待投屏模式
            setTimeout(() => {
                console.log('🚀 自动启动快速等待投屏模式...');
                this.uiController?.quickWaitForCasting();
            }, 2000); // 延迟2秒，让用户看到界面
        }
    }
    
    /**
     * 🚀 自动连接功能
     */
    async autoConnect() {
        const roomInput = document.getElementById('roomIdInput');
        const userNameInput = document.getElementById('userNameInput');
        const serverInput = document.getElementById('serverUrlInput');
        
        if (roomInput?.value && userNameInput?.value && serverInput?.value) {
            console.log('🚀 自动连接到房间...');
            this.uiController?.showInfo('正在自动连接...');
            
            // 模拟点击连接按钮
            await this.connectToRoom();
        }
    }
}

// 页面加载完成后初始化应用程序
document.addEventListener('DOMContentLoaded', () => {
    console.log('页面加载完成，初始化应用程序...');
    new App();
});

// 导出到全局作用域
window.App = App; 