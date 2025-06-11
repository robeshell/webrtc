/**
 * WebRTC 客户端管理器
 * 负责处理 WebRTC 的所有核心功能
 */
class WebRTCClient {
    constructor() {
        // WebRTC 配置
        this.config = {
            iceServers: [
                { urls: 'stun:stun.l.google.com:19302' },
                { urls: 'stun:stun1.l.google.com:19302' },
                { urls: 'stun:stun2.l.google.com:19302' }
            ]
        };

        // 状态管理
        this.peerConnection = null;
        this.localStream = null;
        this.remoteStream = null;
        this.isConnected = false;
        this.isOfferer = false;
        
        // DOM 元素
        this.remoteVideo = document.getElementById('remoteVideo');
        this.videoOverlay = document.getElementById('videoOverlay');
        this.videoControls = document.getElementById('videoControls');
        this.videoInfo = document.getElementById('videoInfo');
        
        // 统计信息
        this.stats = {
            startTime: null,
            bytesReceived: 0,
            packetsReceived: 0,
            frameRate: 0,
            resolution: { width: 0, height: 0 }
        };
        
        // 回调函数
        this.onConnectionStateChange = null;
        this.onTrackReceived = null;
        this.onError = null;
        
        this.setupEventHandlers();
    }

    /**
     * 设置事件处理程序
     */
    setupEventHandlers() {
        // 视频元素事件
        this.remoteVideo.addEventListener('loadedmetadata', () => {
            console.log('远程视频元数据已加载');
            this.hideVideoOverlay();
            this.showVideoControls();
            this.updateVideoInfo();
        });

        this.remoteVideo.addEventListener('playing', () => {
            console.log('远程视频开始播放');
            this.stats.startTime = Date.now();
        });

        this.remoteVideo.addEventListener('error', (event) => {
            console.error('视频播放错误:', event);
            this.showError('视频播放出错');
        });

        // 全屏事件监听
        document.addEventListener('fullscreenchange', () => {
            this.updateFullscreenButton();
        });
    }

    /**
     * 创建 PeerConnection
     */
    async createPeerConnection() {
        try {
            if (this.peerConnection) {
                this.closePeerConnection();
            }

            console.log('创建 PeerConnection...');
            this.peerConnection = new RTCPeerConnection(this.config);

            // 设置事件处理程序
            this.peerConnection.onicecandidate = (event) => {
                if (event.candidate) {
                    console.log('生成 ICE Candidate:', event.candidate);
                    this.onIceCandidate?.(event.candidate);
                }
            };

            this.peerConnection.ontrack = (event) => {
                console.log('接收到远程媒体流:', event);
                this.handleRemoteStream(event.streams[0]);
            };

            this.peerConnection.onconnectionstatechange = () => {
                const state = this.peerConnection.connectionState;
                console.log('连接状态变化:', state);
                this.handleConnectionStateChange(state);
            };

            this.peerConnection.oniceconnectionstatechange = () => {
                const state = this.peerConnection.iceConnectionState;
                console.log('ICE 连接状态变化:', state);
                this.handleIceConnectionStateChange(state);
            };

            this.peerConnection.ondatachannel = (event) => {
                console.log('接收到数据通道:', event.channel);
                this.setupDataChannel(event.channel);
            };

            return this.peerConnection;
            
        } catch (error) {
            console.error('创建 PeerConnection 失败:', error);
            this.showError('创建连接失败');
            throw error;
        }
    }

    /**
     * 处理远程媒体流
     */
    handleRemoteStream(stream) {
        console.log('处理远程媒体流:', stream);
        this.remoteStream = stream;
        this.remoteVideo.srcObject = stream;
        
        // 通知外部组件
        this.onTrackReceived?.(stream);
        
        // 开始统计信息监控
        this.startStatsMonitoring();
    }

    /**
     * 处理连接状态变化
     */
    handleConnectionStateChange(state) {
        this.isConnected = (state === 'connected');
        
        switch (state) {
            case 'connecting':
                console.log('正在建立连接...');
                break;
            case 'connected':
                console.log('连接已建立');
                this.showSuccess('WebRTC 连接成功建立');
                break;
            case 'disconnected':
                console.log('连接已断开');
                this.showVideoOverlay();
                this.hideVideoControls();
                break;
            case 'failed':
                console.log('连接失败');
                this.showError('WebRTC 连接失败');
                this.showVideoOverlay();
                this.hideVideoControls();
                break;
            case 'closed':
                console.log('连接已关闭');
                this.showVideoOverlay();
                this.hideVideoControls();
                break;
        }
        
        // 通知外部组件
        this.onConnectionStateChange?.(state);
    }

    /**
     * 处理 ICE 连接状态变化
     */
    handleIceConnectionStateChange(state) {
        console.log('ICE 连接状态:', state);
        
        if (state === 'failed' || state === 'disconnected') {
            // 尝试重新协商
            this.handleConnectionFailure();
        }
    }

    /**
     * 创建 Offer
     */
    async createOffer() {
        try {
            if (!this.peerConnection) {
                await this.createPeerConnection();
            }

            console.log('创建 Offer...');
            this.isOfferer = true;
            
            const offer = await this.peerConnection.createOffer({
                offerToReceiveVideo: true,
                offerToReceiveAudio: true
            });

            await this.peerConnection.setLocalDescription(offer);
            console.log('本地描述已设置:', offer);

            return offer;
            
        } catch (error) {
            console.error('创建 Offer 失败:', error);
            this.showError('创建连接请求失败');
            throw error;
        }
    }

    /**
     * 创建 Answer
     */
    async createAnswer(offer) {
        try {
            if (!this.peerConnection) {
                await this.createPeerConnection();
            }

            console.log('设置远程描述并创建 Answer...');
            await this.peerConnection.setRemoteDescription(offer);
            
            const answer = await this.peerConnection.createAnswer();
            await this.peerConnection.setLocalDescription(answer);
            
            console.log('Answer 已创建:', answer);
            return answer;
            
        } catch (error) {
            console.error('创建 Answer 失败:', error);
            this.showError('创建连接响应失败');
            throw error;
        }
    }

    /**
     * 设置远程 Answer
     */
    async setRemoteAnswer(answerSdp) {
        try {
            if (!this.peerConnection) {
                throw new Error('PeerConnection 未初始化');
            }

            console.log('设置远程 Answer...', answerSdp);
            
            // 创建RTCSessionDescription对象
            const answer = new RTCSessionDescription({
                type: 'answer',
                sdp: answerSdp
            });
            
            await this.peerConnection.setRemoteDescription(answer);
            console.log('远程描述已设置');
            
        } catch (error) {
            console.error('设置远程 Answer 失败:', error);
            this.showError('设置连接响应失败');
            throw error;
        }
    }

    /**
     * 添加 ICE Candidate
     */
    async addIceCandidate(candidate) {
        try {
            if (!this.peerConnection) {
                console.warn('PeerConnection 未初始化，忽略 ICE Candidate');
                return;
            }

            console.log('添加 ICE Candidate:', candidate);
            await this.peerConnection.addIceCandidate(candidate);
            
        } catch (error) {
            console.error('添加 ICE Candidate 失败:', error);
            // ICE Candidate 失败通常不是致命错误，继续尝试其他候选者
        }
    }

    /**
     * 关闭 PeerConnection
     */
    closePeerConnection() {
        console.log('关闭 PeerConnection...');
        
        if (this.peerConnection) {
            this.peerConnection.close();
            this.peerConnection = null;
        }
        
        if (this.remoteStream) {
            this.remoteStream.getTracks().forEach(track => track.stop());
            this.remoteStream = null;
        }
        
        this.remoteVideo.srcObject = null;
        this.isConnected = false;
        this.isOfferer = false;
        
        // 重置UI
        this.showVideoOverlay();
        this.hideVideoControls();
        this.stopStatsMonitoring();
    }

    /**
     * 显示/隐藏视频覆盖层
     */
    showVideoOverlay() {
        if (this.videoOverlay) {
            this.videoOverlay.style.display = 'flex';
        }
    }

    hideVideoOverlay() {
        if (this.videoOverlay) {
            this.videoOverlay.style.display = 'none';
        }
    }

    /**
     * 显示/隐藏视频控制栏
     */
    showVideoControls() {
        if (this.videoControls) {
            this.videoControls.style.display = 'flex';
        }
    }

    hideVideoControls() {
        if (this.videoControls) {
            this.videoControls.style.display = 'none';
        }
    }

    /**
     * 更新视频信息显示
     */
    updateVideoInfo() {
        if (!this.remoteVideo || !this.videoInfo) return;
        
        const video = this.remoteVideo;
        const info = `${video.videoWidth}x${video.videoHeight}`;
        this.videoInfo.textContent = info;
        
        // 更新统计信息
        this.stats.resolution = {
            width: video.videoWidth,
            height: video.videoHeight
        };
    }

    /**
     * 开始统计信息监控
     */
    startStatsMonitoring() {
        if (this.statsInterval) {
            clearInterval(this.statsInterval);
        }

        this.statsInterval = setInterval(async () => {
            if (this.peerConnection) {
                try {
                    const stats = await this.peerConnection.getStats();
                    this.processStats(stats);
                } catch (error) {
                    console.error('获取统计信息失败:', error);
                }
            }
        }, 1000);
    }

    /**
     * 停止统计信息监控
     */
    stopStatsMonitoring() {
        if (this.statsInterval) {
            clearInterval(this.statsInterval);
            this.statsInterval = null;
        }
    }

    /**
     * 处理统计信息
     */
    processStats(stats) {
        stats.forEach(report => {
            if (report.type === 'inbound-rtp' && report.kind === 'video') {
                this.stats.bytesReceived = report.bytesReceived || 0;
                this.stats.packetsReceived = report.packetsReceived || 0;
                this.stats.frameRate = report.framesPerSecond || 0;
            }
        });
    }

    /**
     * 获取连接统计信息
     */
    getStats() {
        return {
            ...this.stats,
            isConnected: this.isConnected,
            connectionDuration: this.stats.startTime ? 
                Math.floor((Date.now() - this.stats.startTime) / 1000) : 0
        };
    }

    /**
     * 处理连接失败
     */
    handleConnectionFailure() {
        console.log('处理连接失败，尝试重新连接...');
        this.showError('连接中断，正在尝试重新连接...');
        
        // 这里可以实现重连逻辑
        // 由外部组件决定是否重连
        this.onError?.('connection_failed');
    }

    /**
     * 切换视频播放状态
     */
    togglePlayPause() {
        if (!this.remoteVideo) return;
        
        if (this.remoteVideo.paused) {
            this.remoteVideo.play();
        } else {
            this.remoteVideo.pause();
        }
    }

    /**
     * 切换静音状态
     */
    toggleMute() {
        if (!this.remoteVideo) return;
        
        this.remoteVideo.muted = !this.remoteVideo.muted;
        return this.remoteVideo.muted;
    }

    /**
     * 设置音量
     */
    setVolume(volume) {
        if (!this.remoteVideo) return;
        
        this.remoteVideo.volume = Math.max(0, Math.min(1, volume / 100));
    }

    /**
     * 切换全屏
     */
    async toggleFullscreen() {
        const videoContainer = document.getElementById('videoContainer');
        if (!videoContainer) return;

        try {
            if (!document.fullscreenElement) {
                await videoContainer.requestFullscreen();
                videoContainer.classList.add('fullscreen');
            } else {
                await document.exitFullscreen();
                videoContainer.classList.remove('fullscreen');
            }
        } catch (error) {
            console.error('全屏切换失败:', error);
        }
    }

    /**
     * 更新全屏按钮状态
     */
    updateFullscreenButton() {
        const fullscreenBtn = document.getElementById('fullscreenBtn');
        if (!fullscreenBtn) return;

        if (document.fullscreenElement) {
            fullscreenBtn.textContent = '⛶';
            fullscreenBtn.title = '退出全屏';
        } else {
            fullscreenBtn.textContent = '⛶';
            fullscreenBtn.title = '全屏';
        }
    }

    /**
     * 截图功能
     */
    takeScreenshot() {
        if (!this.remoteVideo) return;

        try {
            const canvas = document.createElement('canvas');
            const ctx = canvas.getContext('2d');
            
            canvas.width = this.remoteVideo.videoWidth;
            canvas.height = this.remoteVideo.videoHeight;
            
            ctx.drawImage(this.remoteVideo, 0, 0);
            
            // 下载截图
            const link = document.createElement('a');
            link.download = `screenshot_${new Date().getTime()}.png`;
            link.href = canvas.toDataURL();
            link.click();
            
            this.showSuccess('截图已保存');
            
        } catch (error) {
            console.error('截图失败:', error);
            this.showError('截图失败');
        }
    }

    /**
     * 显示成功消息
     */
    showSuccess(message) {
        console.log('成功:', message);
        // 这个方法会被外部的通知系统使用
        if (window.UIController) {
            window.UIController.showNotification(message, 'success');
        }
    }

    /**
     * 显示错误消息
     */
    showError(message) {
        console.error('错误:', message);
        // 这个方法会被外部的通知系统使用
        if (window.UIController) {
            window.UIController.showNotification(message, 'error');
        }
    }

    /**
     * 设置回调函数
     */
    setCallbacks(callbacks) {
        this.onIceCandidate = callbacks.onIceCandidate;
        this.onConnectionStateChange = callbacks.onConnectionStateChange;
        this.onTrackReceived = callbacks.onTrackReceived;
        this.onError = callbacks.onError;
    }
}

// 导出到全局作用域
window.WebRTCClient = WebRTCClient; 