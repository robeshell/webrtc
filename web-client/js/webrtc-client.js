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
     * 处理远程视频流
     */
    handleRemoteStream(stream) {
        console.log('🎥 收到远程视频流');
        this.remoteStream = stream;
        
        if (this.remoteVideo) {
        this.remoteVideo.srcObject = stream;
        
            // 🚨 强制设置视频元素属性以优化分辨率显示
            this.remoteVideo.style.objectFit = 'fill';  // 强制填充整个容器
            this.remoteVideo.style.width = '100%';      // 占满容器宽度
            this.remoteVideo.style.height = '100%';     // 占满容器高度
            
            // 监听视频元数据加载完成事件
            this.remoteVideo.onloadedmetadata = () => {
                console.log('📺 视频元数据加载完成');
                this.updateVideoInfo();
                
                // 🚨 强制设置更高的显示分辨率
                const actualWidth = this.remoteVideo.videoWidth;
                const actualHeight = this.remoteVideo.videoHeight;
                
                console.log(`📐 原始视频分辨率: ${actualWidth}×${actualHeight}`);
                
                // 🚀 支持多种分辨率的智能显示优化
                if (actualWidth === 1920 && actualHeight === 1080) {
                    // 🎉 原生1080p，无需拉伸
                    console.log(`✅ 检测到原生1080p分辨率: ${actualWidth}×${actualHeight}`);
                    this.remoteVideo.style.transform = 'none';
                    if (this.videoInfo) {
                        this.videoInfo.textContent = `${actualWidth}x${actualHeight} (原生1080p)`;
                    }
                } else if ((actualWidth === 640 && actualHeight === 360) || 
                          (actualWidth === 480 && actualHeight === 270)) {
                    // 低分辨率需要拉伸显示
                    const scale = actualWidth === 480 ? 4 : 3; // 480->1920需要4倍，640->1920需要3倍
                    const targetWidth = 1920;
                    const targetHeight = 1080;
                    
                    console.log(`🚨 检测到${actualWidth}x${actualHeight}，强制拉伸为${targetWidth}x${targetHeight}显示`);
                    this.remoteVideo.style.transform = `scale(${scale})`;
                    this.remoteVideo.style.transformOrigin = 'center';
                    
                    // 更新显示信息
                    if (this.videoInfo) {
                        this.videoInfo.textContent = `${targetWidth}x${targetHeight} (拉伸自 ${actualWidth}x${actualHeight})`;
                    }
                } else {
                    // 其他分辨率，保持原样
                    this.remoteVideo.style.transform = 'none';
                    if (this.videoInfo) {
                        this.videoInfo.textContent = `${actualWidth}x${actualHeight}`;
                    }
                }
            };
            
            this.remoteVideo.onplay = () => {
                console.log('🎬 远程视频开始播放');
                this.startStatsCollection();
            };
            
            this.remoteVideo.onerror = (error) => {
                console.error('❌ 远程视频播放错误:', error);
            };
        }
        
        if (this.onTrackReceived) {
            this.onTrackReceived(stream);
        }
    }

    /**
     * 处理连接状态变化
     */
    handleConnectionStateChange(state) {
        console.log('连接状态变化:', state);
        
        if (this.onConnectionStateChange) {
            this.onConnectionStateChange(state);
        }
        
        switch (state) {
            case 'connected':
                console.log('连接已建立');
                this.showSuccess('WebRTC 连接成功建立');
                break;
            case 'disconnected':
                console.log('连接已断开');
                this.showError('WebRTC 连接已断开');
                break;
            case 'failed':
                console.log('连接失败');
                this.showError('WebRTC 连接失败');
                break;
        }
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
     * 修改SDP以支持高分辨率视频
     * 确保Web端能正确接收高分辨率视频流
     */
    modifySdpForHighQuality(sessionDescription) {
        const originalSdp = sessionDescription.sdp;
        const modifiedSdp = [];
        
        const lines = originalSdp.split('\r\n');
        let inVideoSection = false;
        let hasSetBandwidth = false;
        
        console.log('🔧 Web端: 正在修改SDP以支持高分辨率视频接收...');
        
        for (const line of lines) {
            if (line.startsWith('m=video')) {
                inVideoSection = true;
                modifiedSdp.push(line);
                console.log('检测到视频媒体部分');
            } else if (line.startsWith('m=audio') || line.startsWith('m=application')) {
                inVideoSection = false;
                modifiedSdp.push(line);
            } else if (inVideoSection && line.startsWith('a=rtpmap:') && line.includes('H264')) {
                modifiedSdp.push(line);
                
                // 提取payload type
                const payloadType = line.split(':')[1].split(' ')[0];
                console.log('找到H.264编码器，Payload Type:', payloadType);
                
                // 添加高带宽约束以支持高分辨率
                if (!hasSetBandwidth) {
                    // 设置高带宽限制 (支持高达8Mbps的视频流)
                    modifiedSdp.push('b=CT:8000');
                    modifiedSdp.push('b=AS:8000');
                    modifiedSdp.push('b=TIAS:8000000');
                    
                    // 添加支持高分辨率的H.264参数
                    modifiedSdp.push(`a=fmtp:${payloadType} level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e032`);
                    
                    hasSetBandwidth = true;
                    console.log('✅ Web端: 已设置高分辨率支持参数');
                    console.log('   🎯 最大带宽: 8000kbps');
                    console.log('   🏗️ H.264 Profile: High Level 5.0 (支持1080p@30fps)');
                }
            } else if (inVideoSection && line.startsWith('a=fmtp:') && line.includes('profile-level-id')) {
                // 跳过原有的profile-level-id设置
                console.log('跳过原有的profile-level-id设置:', line);
                continue;
            } else if (inVideoSection && (line.startsWith('b=CT:') || line.startsWith('b=AS:') || line.startsWith('b=TIAS:'))) {
                // 跳过原有的带宽设置
                console.log('跳过原有的带宽设置:', line);
                continue;
            } else {
                modifiedSdp.push(line);
            }
        }
        
        const finalSdp = modifiedSdp.join('\r\n');
        console.log('🎉 Web端: SDP修改完成！现在应该能接收高分辨率视频流');
        
        // 输出关键修改部分用于调试
        console.log('Web端SDP修改片段:');
        finalSdp.split('\r\n').forEach(line => {
            if (line.includes('H264') || line.includes('b=') || line.includes('fmtp')) {
                console.log('  ', line);
            }
        });
        
        return new RTCSessionDescription({
            type: sessionDescription.type,
            sdp: finalSdp
        });
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
        
        // 🚨 强制调试：打印所有视频属性
        console.error('🚨🚨🚨 Web端视频信息强制调试 🚨🚨🚨');
        console.error('📺 videoWidth:', video.videoWidth);
        console.error('📺 videoHeight:', video.videoHeight);
        console.error('📺 clientWidth:', video.clientWidth);
        console.error('📺 clientHeight:', video.clientHeight);
        console.error('📺 offsetWidth:', video.offsetWidth);
        console.error('📺 offsetHeight:', video.offsetHeight);
        console.error('📺 naturalWidth:', video.naturalWidth || 'N/A');
        console.error('📺 naturalHeight:', video.naturalHeight || 'N/A');
        console.error('🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨🚨');
        
        const info = `${video.videoWidth}x${video.videoHeight}`;
        this.videoInfo.textContent = info;
        
        // 更新统计信息
        this.stats.resolution = {
            width: video.videoWidth,
            height: video.videoHeight
        };
    }

    /**
     * 开始统计信息收集
     */
    startStatsCollection() {
        if (this.statsInterval) {
            clearInterval(this.statsInterval);
        }

        this.statsInterval = setInterval(async () => {
            if (this.peerConnection) {
                try {
                    const stats = await this.peerConnection.getStats();
                    this.processStats(stats);
                    // 🚨 检查分辨率并尝试优化
                    this.checkAndOptimizeResolution(stats);
                } catch (error) {
                    console.error('获取统计信息失败:', error);
                }
            }
        }, 1000);
    }
    
    /**
     * 检查并优化分辨率
     */
    checkAndOptimizeResolution(stats) {
        let currentResolution = null;
        
        stats.forEach(report => {
            if (report.type === 'inbound-rtp' && report.kind === 'video') {
                if (report.frameWidth && report.frameHeight) {
                    currentResolution = { width: report.frameWidth, height: report.frameHeight };
                    
                    // 🚨 如果分辨率不符合预期，尝试优化
                    if (report.frameWidth === 640 && report.frameHeight === 360) {
                        console.log('🚨 检测到实际接收分辨率为640x360，尝试优化...');
                        
                        // 记录详细统计信息
                        console.log('📊 详细统计信息:');
                        console.log('  - 帧宽度:', report.frameWidth);
                        console.log('  - 帧高度:', report.frameHeight);
                        console.log('  - 码率:', report.bytesReceived);
                        console.log('  - 帧率:', report.framesPerSecond);
                        console.log('  - 丢包率:', report.packetsLost);
                        
                        // 尝试请求更高分辨率
                        this.requestHigherResolution();
                    }
                }
            }
        });
        
        // 更新分辨率显示
        if (currentResolution && this.videoInfo) {
            const displayText = `${currentResolution.width}x${currentResolution.height}`;
            if (this.videoInfo.textContent !== displayText) {
                this.videoInfo.textContent = displayText;
                console.log(`📐 分辨率更新: ${displayText}`);
            }
        }
    }
    
    /**
     * 请求更高分辨率
     */
    async requestHigherResolution() {
        if (!this.peerConnection) return;
        
        try {
            // 获取当前的发送者
            const senders = this.peerConnection.getSenders();
            const videoSender = senders.find(sender => 
                sender.track && sender.track.kind === 'video'
            );
            
            if (videoSender) {
                // 获取发送参数
                const params = videoSender.getParameters();
                
                // 修改编码参数以请求更高分辨率
                if (params.encodings && params.encodings.length > 0) {
                    params.encodings[0].maxBitrate = 8000000; // 8Mbps支持1080p
                    params.encodings[0].scaleResolutionDownBy = 1; // 不降低分辨率
                    
                    // 应用新参数
                    await videoSender.setParameters(params);
                    console.log('✅ 已请求1080p分辨率和8Mbps码率');
                }
            }
        } catch (error) {
            console.error('请求更高分辨率失败:', error);
        }
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
        // 安全调用UIController，如果不存在则静默处理
        try {
            if (typeof window !== 'undefined' && window.UIController && window.UIController.showNotification) {
            window.UIController.showNotification(message, 'success');
            }
        } catch (error) {
            // 忽略UIController错误，继续执行
            console.log('UIController不可用，消息已在控制台显示');
        }
    }

    /**
     * 显示错误消息
     */
    showError(message) {
        console.error('错误:', message);
        // 安全调用UIController，如果不存在则静默处理
        try {
            if (typeof window !== 'undefined' && window.UIController && window.UIController.showNotification) {
            window.UIController.showNotification(message, 'error');
            }
        } catch (error) {
            // 忽略UIController错误，继续执行
            console.log('UIController不可用，错误消息已在控制台显示');
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