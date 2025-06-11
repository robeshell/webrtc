/**
 * UI 控制器
 * 负责管理用户界面的交互和状态显示
 */
class UIController {
    constructor() {
        // DOM 元素引用
        this.elements = {
            // 状态显示
            serverStatus: document.getElementById('serverStatus'),
            roomStatus: document.getElementById('roomStatus'),
            connectionStatus: document.getElementById('connectionStatus'),
            
            // 输入控件
            roomIdInput: document.getElementById('roomIdInput'),
            userNameInput: document.getElementById('userNameInput'),
            serverUrlInput: document.getElementById('serverUrlInput'),
            
            // 按钮
            generateRoomBtn: document.getElementById('generateRoomBtn'),
            connectBtn: document.getElementById('connectBtn'),
            disconnectBtn: document.getElementById('disconnectBtn'),
            
            // 视频控制
            playPauseBtn: document.getElementById('playPauseBtn'),
            muteBtn: document.getElementById('muteBtn'),
            volumeSlider: document.getElementById('volumeSlider'),
            fullscreenBtn: document.getElementById('fullscreenBtn'),
            screenshotBtn: document.getElementById('screenshotBtn'),
            
            // 日志和通知
            logContent: document.getElementById('logContent'),
            clearLogBtn: document.getElementById('clearLogBtn'),
            toggleLogBtn: document.getElementById('toggleLogBtn'),
            notification: document.getElementById('notification'),
            notificationMessage: document.getElementById('notificationMessage'),
            closeNotification: document.getElementById('closeNotification'),
            
            // 房间信息
            roomInfo: document.getElementById('roomInfo'),
            currentRoomId: document.getElementById('currentRoomId'),
            userCount: document.getElementById('userCount'),
            
            // 统计面板
            statsPanel: document.getElementById('statsPanel'),
            statsContent: document.getElementById('statsContent'),
            toggleStatsBtn: document.getElementById('toggleStatsBtn')
        };
        
        // 状态管理
        this.isLogVisible = true;
        this.isStatsVisible = false;
        this.currentNotificationTimeout = null;
        
        // 绑定事件处理程序
        this.setupEventListeners();
        
        // 初始化UI状态
        this.initializeUI();
    }

    /**
     * 设置事件监听器
     */
    setupEventListeners() {
        // 房间ID生成
        if (this.elements.generateRoomBtn) {
            this.elements.generateRoomBtn.addEventListener('click', () => {
                this.generateRoomId();
            });
        }

        // 连接控制
        if (this.elements.connectBtn) {
            this.elements.connectBtn.addEventListener('click', () => {
                this.handleConnect();
            });
        }

        if (this.elements.disconnectBtn) {
            this.elements.disconnectBtn.addEventListener('click', () => {
                this.handleDisconnect();
            });
        }

        // 视频控制
        if (this.elements.playPauseBtn) {
            this.elements.playPauseBtn.addEventListener('click', () => {
                this.togglePlayPause();
            });
        }

        if (this.elements.muteBtn) {
            this.elements.muteBtn.addEventListener('click', () => {
                this.toggleMute();
            });
        }

        if (this.elements.volumeSlider) {
            this.elements.volumeSlider.addEventListener('input', (e) => {
                this.setVolume(e.target.value);
            });
        }

        if (this.elements.fullscreenBtn) {
            this.elements.fullscreenBtn.addEventListener('click', () => {
                this.toggleFullscreen();
            });
        }

        if (this.elements.screenshotBtn) {
            this.elements.screenshotBtn.addEventListener('click', () => {
                this.takeScreenshot();
            });
        }

        // 日志控制
        if (this.elements.clearLogBtn) {
            this.elements.clearLogBtn.addEventListener('click', () => {
                this.clearLog();
            });
        }

        if (this.elements.toggleLogBtn) {
            this.elements.toggleLogBtn.addEventListener('click', () => {
                this.toggleLogPanel();
            });
        }

        // 通知关闭
        if (this.elements.closeNotification) {
            this.elements.closeNotification.addEventListener('click', () => {
                this.hideNotification();
            });
        }

        // 统计面板切换
        if (this.elements.toggleStatsBtn) {
            this.elements.toggleStatsBtn.addEventListener('click', () => {
                this.toggleStatsPanel();
            });
        }

        // 键盘快捷键
        document.addEventListener('keydown', (e) => {
            this.handleKeyboardShortcuts(e);
        });

        // 输入框回车事件
        [this.elements.roomIdInput, this.elements.userNameInput, this.elements.serverUrlInput]
        .forEach(input => {
            if (input) {
                input.addEventListener('keypress', (e) => {
                    if (e.key === 'Enter') {
                        this.handleConnect();
                    }
                });
            }
        });
    }

    /**
     * 初始化UI状态
     */
    initializeUI() {
        // 生成默认用户名
        if (this.elements.userNameInput) {
            this.elements.userNameInput.value = `用户_${Math.floor(Math.random() * 1000)}`;
        }

        // 生成默认房间ID
        this.generateRoomId();

        // 初始化状态显示
        this.updateServerStatus('disconnected');
        this.updateRoomStatus('未加入');
        this.updateConnectionStatus('等待中');

        // 初始化按钮状态
        this.updateButtonStates(false, true);

        // 记录初始日志
        this.addLog('Web 客户端已加载，等待连接...', 'info');
    }

    /**
     * 生成随机房间ID
     */
    generateRoomId() {
        const roomId = Math.random().toString(36).substring(2, 8).toUpperCase();
        if (this.elements.roomIdInput) {
            this.elements.roomIdInput.value = roomId;
        }
        this.addLog(`生成房间ID: ${roomId}`, 'info');
    }

    /**
     * 处理连接
     */
    handleConnect() {
        const roomId = this.elements.roomIdInput?.value.trim();
        const userName = this.elements.userNameInput?.value.trim();
        const serverUrl = this.elements.serverUrlInput?.value.trim();

        if (!roomId || !userName || !serverUrl) {
            this.showNotification('请填写所有必需字段', 'error');
            return;
        }

        // 触发外部连接处理
        if (window.app && window.app.connect) {
            window.app.connect(roomId, userName, serverUrl);
        }
    }

    /**
     * 处理断开连接
     */
    handleDisconnect() {
        if (window.app && window.app.disconnect) {
            window.app.disconnect();
        }
    }

    /**
     * 更新服务器状态
     */
    updateServerStatus(status) {
        if (!this.elements.serverStatus) return;

        this.elements.serverStatus.className = 'status-value ' + status;
        
        switch (status) {
            case 'connected':
                this.elements.serverStatus.textContent = '已连接';
                break;
            case 'connecting':
                this.elements.serverStatus.textContent = '连接中';
                break;
            case 'disconnected':
                this.elements.serverStatus.textContent = '未连接';
                break;
            default:
                this.elements.serverStatus.textContent = status;
        }
    }

    /**
     * 更新房间状态
     */
    updateRoomStatus(status) {
        if (!this.elements.roomStatus) return;
        this.elements.roomStatus.textContent = status;
    }

    /**
     * 更新连接状态
     */
    updateConnectionStatus(status) {
        if (!this.elements.connectionStatus) return;
        this.elements.connectionStatus.textContent = status;
    }

    /**
     * 更新按钮状态
     */
    updateButtonStates(isConnected, canConnect = true) {
        if (this.elements.connectBtn) {
            this.elements.connectBtn.disabled = !canConnect || isConnected;
        }
        if (this.elements.disconnectBtn) {
            this.elements.disconnectBtn.disabled = !isConnected;
        }
    }

    /**
     * 更新房间信息显示
     */
    updateRoomInfo(roomId, userCount = 0) {
        if (this.elements.roomInfo) {
            this.elements.roomInfo.style.display = roomId ? 'block' : 'none';
        }
        if (this.elements.currentRoomId) {
            this.elements.currentRoomId.textContent = roomId || '';
        }
        if (this.elements.userCount) {
            this.elements.userCount.textContent = userCount.toString();
        }
    }

    /**
     * 视频控制方法
     */
    togglePlayPause() {
        if (window.webrtcClient) {
            window.webrtcClient.togglePlayPause();
            this.updatePlayPauseButton();
        }
    }

    toggleMute() {
        if (window.webrtcClient) {
            const isMuted = window.webrtcClient.toggleMute();
            this.updateMuteButton(isMuted);
        }
    }

    setVolume(volume) {
        if (window.webrtcClient) {
            window.webrtcClient.setVolume(volume);
        }
    }

    toggleFullscreen() {
        if (window.webrtcClient) {
            window.webrtcClient.toggleFullscreen();
        }
    }

    takeScreenshot() {
        if (window.webrtcClient) {
            window.webrtcClient.takeScreenshot();
        }
    }

    /**
     * 更新播放/暂停按钮
     */
    updatePlayPauseButton() {
        const video = document.getElementById('remoteVideo');
        const btn = this.elements.playPauseBtn;
        if (!video || !btn) return;

        if (video.paused) {
            btn.textContent = '▶️';
            btn.title = '播放';
        } else {
            btn.textContent = '⏸️';
            btn.title = '暂停';
        }
    }

    /**
     * 更新静音按钮
     */
    updateMuteButton(isMuted) {
        const btn = this.elements.muteBtn;
        if (!btn) return;

        if (isMuted) {
            btn.textContent = '🔇';
            btn.title = '取消静音';
        } else {
            btn.textContent = '🔊';
            btn.title = '静音';
        }
    }

    /**
     * 日志管理
     */
    addLog(message, type = 'info') {
        if (!this.elements.logContent) return;

        const logItem = document.createElement('div');
        logItem.className = `log-item ${type}`;
        
        const timeSpan = document.createElement('span');
        timeSpan.className = 'log-time';
        timeSpan.textContent = `[${new Date().toLocaleTimeString()}]`;
        
        const messageSpan = document.createElement('span');
        messageSpan.className = 'log-message';
        messageSpan.textContent = message;
        
        logItem.appendChild(timeSpan);
        logItem.appendChild(messageSpan);
        
        this.elements.logContent.appendChild(logItem);
        
        // 自动滚动到底部
        this.elements.logContent.scrollTop = this.elements.logContent.scrollHeight;
        
        // 限制日志条数
        const logItems = this.elements.logContent.querySelectorAll('.log-item');
        if (logItems.length > 100) {
            logItems[0].remove();
        }
    }

    clearLog() {
        if (this.elements.logContent) {
            this.elements.logContent.innerHTML = '';
            this.addLog('日志已清空', 'info');
        }
    }

    toggleLogPanel() {
        const logPanel = document.getElementById('logPanel');
        const logContent = this.elements.logContent;
        const toggleBtn = this.elements.toggleLogBtn;
        
        if (!logPanel || !logContent || !toggleBtn) return;

        this.isLogVisible = !this.isLogVisible;
        
        if (this.isLogVisible) {
            logContent.style.display = 'block';
            toggleBtn.textContent = '收起';
        } else {
            logContent.style.display = 'none';
            toggleBtn.textContent = '展开';
        }
    }

    /**
     * 通知管理
     */
    showNotification(message, type = 'info', duration = 3000) {
        if (!this.elements.notification || !this.elements.notificationMessage) return;

        // 清除之前的定时器
        if (this.currentNotificationTimeout) {
            clearTimeout(this.currentNotificationTimeout);
        }

        // 设置消息和样式
        this.elements.notificationMessage.textContent = message;
        this.elements.notification.className = `notification ${type}`;
        this.elements.notification.style.display = 'block';

        // 添加到日志
        this.addLog(message, type);

        // 自动隐藏
        this.currentNotificationTimeout = setTimeout(() => {
            this.hideNotification();
        }, duration);
    }

    hideNotification() {
        if (this.elements.notification) {
            this.elements.notification.style.display = 'none';
        }
        if (this.currentNotificationTimeout) {
            clearTimeout(this.currentNotificationTimeout);
            this.currentNotificationTimeout = null;
        }
    }

    /**
     * 统计面板管理
     */
    toggleStatsPanel() {
        if (!this.elements.statsPanel) return;

        this.isStatsVisible = !this.isStatsVisible;
        
        if (this.isStatsVisible) {
            this.elements.statsPanel.style.display = 'block';
            this.startStatsUpdate();
        } else {
            this.elements.statsPanel.style.display = 'none';
            this.stopStatsUpdate();
        }
    }

    startStatsUpdate() {
        if (this.statsInterval) {
            clearInterval(this.statsInterval);
        }

        this.statsInterval = setInterval(() => {
            this.updateStats();
        }, 1000);
    }

    stopStatsUpdate() {
        if (this.statsInterval) {
            clearInterval(this.statsInterval);
            this.statsInterval = null;
        }
    }

    updateStats() {
        if (!window.webrtcClient || !this.elements.statsContent) return;

        const stats = window.webrtcClient.getStats();
        const html = `
            <div>连接状态: ${stats.isConnected ? '已连接' : '未连接'}</div>
            <div>连接时长: ${stats.connectionDuration}秒</div>
            <div>分辨率: ${stats.resolution.width}x${stats.resolution.height}</div>
            <div>帧率: ${stats.frameRate}fps</div>
            <div>接收字节: ${this.formatBytes(stats.bytesReceived)}</div>
            <div>接收包数: ${stats.packetsReceived}</div>
        `;
        
        this.elements.statsContent.innerHTML = html;
    }

    /**
     * 格式化字节数
     */
    formatBytes(bytes) {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }

    /**
     * 键盘快捷键处理
     */
    handleKeyboardShortcuts(event) {
        // 避免在输入框中触发快捷键
        if (event.target.tagName === 'INPUT') return;

        switch (event.key.toLowerCase()) {
            case ' ':
                event.preventDefault();
                this.togglePlayPause();
                break;
            case 'm':
                this.toggleMute();
                break;
            case 'f':
                this.toggleFullscreen();
                break;
            case 's':
                if (event.ctrlKey) {
                    event.preventDefault();
                    this.takeScreenshot();
                }
                break;
            case 'escape':
                if (document.fullscreenElement) {
                    document.exitFullscreen();
                }
                break;
        }
    }

    /**
     * 获取当前连接配置
     */
    getConnectionConfig() {
        return {
            roomId: this.elements.roomIdInput?.value.trim() || '',
            userName: this.elements.userNameInput?.value.trim() || '',
            serverUrl: this.elements.serverUrlInput?.value.trim() || ''
        };
    }

    /**
     * 设置连接配置
     */
    setConnectionConfig(config) {
        if (config.roomId && this.elements.roomIdInput) {
            this.elements.roomIdInput.value = config.roomId;
        }
        if (config.userName && this.elements.userNameInput) {
            this.elements.userNameInput.value = config.userName;
        }
        if (config.serverUrl && this.elements.serverUrlInput) {
            this.elements.serverUrlInput.value = config.serverUrl;
        }
    }

    /**
     * 显示错误状态
     */
    showError(message) {
        this.showNotification(message, 'error');
        this.updateConnectionStatus('错误');
    }

    /**
     * 显示成功状态
     */
    showSuccess(message) {
        this.showNotification(message, 'success');
    }

    /**
     * 重置UI到初始状态
     */
    reset() {
        this.updateServerStatus('disconnected');
        this.updateRoomStatus('未加入');
        this.updateConnectionStatus('等待中');
        this.updateButtonStates(false, true);
        this.updateRoomInfo('', 0);
        this.hideNotification();
    }
}

// 导出到全局作用域
window.UIController = UIController; 