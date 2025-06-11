const express = require('express');
const { createServer } = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const helmet = require('helmet');
const compression = require('compression');
const { v4: uuidv4 } = require('uuid');
const path = require('path');

// 创建 Express 应用
const app = express();
const server = createServer(app);

// 配置 Socket.IO
const io = new Server(server, {
    cors: {
        origin: "*", // 生产环境中应该设置具体的域名
        methods: ["GET", "POST"],
        credentials: true
    },
    transports: ['websocket', 'polling']
});

// 中间件配置
app.use(helmet({
    contentSecurityPolicy: false // 允许加载外部资源
}));
app.use(compression());
app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, '../web-client')));

// 房间和用户管理
const rooms = new Map(); // roomId -> { users: Set, createdAt: Date, settings: {} }
const users = new Map(); // socketId -> { userId, roomId, userType, joinedAt }

// 配置常量
const CONFIG = {
    MAX_USERS_PER_ROOM: 10,
    ROOM_TIMEOUT: 24 * 60 * 60 * 1000, // 24小时
    HEARTBEAT_INTERVAL: 30000, // 30秒
    CONNECTION_TIMEOUT: 60000 // 60秒
};

// 消息类型定义
const MESSAGE_TYPES = {
    // 房间管理
    JOIN_ROOM: 'join_room',
    LEAVE_ROOM: 'leave_room',
    ROOM_JOINED: 'room_joined',
    ROOM_LEFT: 'room_left',
    USER_JOINED: 'user_joined',
    USER_LEFT: 'user_left',
    
    // WebRTC 信令
    OFFER: 'offer',
    ANSWER: 'answer',
    ICE_CANDIDATE: 'ice_candidate',
    
    // 心跳和状态
    PING: 'ping',
    PONG: 'pong',
    ERROR: 'error',
    
    // 房间信息
    ROOM_INFO: 'room_info',
    USER_LIST: 'user_list'
};

/**
 * 日志工具
 */
class Logger {
    static info(message, data = {}) {
        console.log(`[INFO] ${new Date().toISOString()} - ${message}`, data);
    }
    
    static warn(message, data = {}) {
        console.warn(`[WARN] ${new Date().toISOString()} - ${message}`, data);
    }
    
    static error(message, error = null) {
        console.error(`[ERROR] ${new Date().toISOString()} - ${message}`, error);
    }
    
    static debug(message, data = {}) {
        if (process.env.NODE_ENV === 'development') {
            console.log(`[DEBUG] ${new Date().toISOString()} - ${message}`, data);
        }
    }
}

/**
 * 房间管理工具
 */
class RoomManager {
    static createRoom(roomId) {
        if (!rooms.has(roomId)) {
            rooms.set(roomId, {
                users: new Set(),
                createdAt: new Date(),
                settings: {
                    maxUsers: CONFIG.MAX_USERS_PER_ROOM,
                    allowAnonymous: true
                }
            });
            Logger.info(`房间已创建: ${roomId}`);
        }
        return rooms.get(roomId);
    }
    
    static getRoomInfo(roomId) {
        const room = rooms.get(roomId);
        if (!room) return null;
        
        return {
            roomId,
            userCount: room.users.size,
            maxUsers: room.settings.maxUsers,
            createdAt: room.createdAt,
            users: Array.from(room.users).map(socketId => {
                const user = users.get(socketId);
                return user ? {
                    userId: user.userId,
                    userType: user.userType,
                    joinedAt: user.joinedAt
                } : null;
            }).filter(Boolean)
        };
    }
    
    static addUserToRoom(socketId, roomId, userId, userType = 'viewer') {
        const room = this.createRoom(roomId);
        
        if (room.users.size >= room.settings.maxUsers) {
            throw new Error('房间已满');
        }
        
        room.users.add(socketId);
        users.set(socketId, {
            userId,
            roomId,
            userType,
            joinedAt: new Date()
        });
        
        Logger.info(`用户加入房间`, { userId, roomId, userType });
        return room;
    }
    
    static removeUserFromRoom(socketId) {
        const user = users.get(socketId);
        if (!user) return null;
        
        const room = rooms.get(user.roomId);
        if (room) {
            room.users.delete(socketId);
            
            // 如果房间为空，删除房间
            if (room.users.size === 0) {
                rooms.delete(user.roomId);
                Logger.info(`房间已删除: ${user.roomId}`);
            }
        }
        
        users.delete(socketId);
        Logger.info(`用户离开房间`, { userId: user.userId, roomId: user.roomId });
        return user;
    }
    
    static cleanupExpiredRooms() {
        const now = new Date();
        for (const [roomId, room] of rooms.entries()) {
            if (now - room.createdAt > CONFIG.ROOM_TIMEOUT) {
                rooms.delete(roomId);
                Logger.info(`清理过期房间: ${roomId}`);
            }
        }
    }
}

/**
 * 消息处理器
 */
class MessageHandler {
    static handleJoinRoom(socket, data) {
        try {
            const { roomId, userId, userType = 'viewer' } = data;
            
            if (!roomId || !userId) {
                throw new Error('房间ID和用户ID是必需的');
            }
            
            // 如果用户已经在其他房间，先离开
            const currentUser = users.get(socket.id);
            if (currentUser) {
                this.handleLeaveRoom(socket);
            }
            
            // 加入房间
            const room = RoomManager.addUserToRoom(socket.id, roomId, userId, userType);
            socket.join(roomId);
            
            // 通知用户加入成功
            socket.emit(MESSAGE_TYPES.ROOM_JOINED, {
                roomId,
                userId,
                userType,
                roomInfo: RoomManager.getRoomInfo(roomId)
            });
            
            // 通知房间内其他用户
            socket.to(roomId).emit(MESSAGE_TYPES.USER_JOINED, {
                userId,
                userType,
                joinedAt: new Date()
            });
            
            Logger.info(`用户加入房间成功`, { userId, roomId, userType });
            
        } catch (error) {
            Logger.error('处理加入房间失败', error);
            socket.emit(MESSAGE_TYPES.ERROR, {
                message: error.message,
                code: 'JOIN_ROOM_ERROR'
            });
        }
    }
    
    static handleLeaveRoom(socket) {
        const user = RoomManager.removeUserFromRoom(socket.id);
        if (user) {
            socket.leave(user.roomId);
            
            // 通知房间内其他用户
            socket.to(user.roomId).emit(MESSAGE_TYPES.USER_LEFT, {
                userId: user.userId,
                leftAt: new Date()
            });
            
            // 通知用户离开成功
            socket.emit(MESSAGE_TYPES.ROOM_LEFT, {
                roomId: user.roomId,
                userId: user.userId
            });
        }
    }
    
    static handleOffer(socket, data) {
        const { targetUserId, sdp } = data;
        const user = users.get(socket.id);
        
        if (!user) {
            socket.emit(MESSAGE_TYPES.ERROR, { message: '用户未加入房间' });
            return;
        }
        
        // 转发 Offer 到目标用户
        socket.to(user.roomId).emit(MESSAGE_TYPES.OFFER, {
            fromUserId: user.userId,
            sdp,
            timestamp: new Date()
        });
        
        Logger.debug('转发 Offer', { from: user.userId, to: targetUserId });
    }
    
    static handleAnswer(socket, data) {
        const { targetUserId, sdp } = data;
        const user = users.get(socket.id);
        
        if (!user) {
            socket.emit(MESSAGE_TYPES.ERROR, { message: '用户未加入房间' });
            return;
        }
        
        // 转发 Answer 到目标用户
        socket.to(user.roomId).emit(MESSAGE_TYPES.ANSWER, {
            fromUserId: user.userId,
            sdp,
            timestamp: new Date()
        });
        
        Logger.debug('转发 Answer', { from: user.userId, to: targetUserId });
    }
    
    static handleIceCandidate(socket, data) {
        const { targetUserId, candidate, sdpMid, sdpMLineIndex } = data;
        const user = users.get(socket.id);
        
        if (!user) {
            socket.emit(MESSAGE_TYPES.ERROR, { message: '用户未加入房间' });
            return;
        }
        
        // 转发 ICE Candidate 到目标用户
        socket.to(user.roomId).emit(MESSAGE_TYPES.ICE_CANDIDATE, {
            fromUserId: user.userId,
            candidate,
            sdpMid,
            sdpMLineIndex,
            timestamp: new Date()
        });
        
        Logger.debug('转发 ICE Candidate', { from: user.userId, to: targetUserId });
    }
    
    static handlePing(socket) {
        socket.emit(MESSAGE_TYPES.PONG, {
            timestamp: new Date().toISOString()
        });
    }
    
    static handleGetRoomInfo(socket, data) {
        const { roomId } = data;
        const roomInfo = RoomManager.getRoomInfo(roomId);
        
        socket.emit(MESSAGE_TYPES.ROOM_INFO, {
            roomInfo,
            success: !!roomInfo
        });
    }
}

// Socket.IO 连接处理
io.on('connection', (socket) => {
    Logger.info(`新的Socket连接: ${socket.id}`);
    
    // 设置连接超时
    const connectionTimeout = setTimeout(() => {
        if (!users.has(socket.id)) {
            Logger.warn(`连接超时，断开未认证连接: ${socket.id}`);
            socket.disconnect();
        }
    }, CONFIG.CONNECTION_TIMEOUT);
    
    // 加入房间
    socket.on(MESSAGE_TYPES.JOIN_ROOM, (data) => {
        clearTimeout(connectionTimeout);
        MessageHandler.handleJoinRoom(socket, data);
    });
    
    // 离开房间
    socket.on(MESSAGE_TYPES.LEAVE_ROOM, () => {
        MessageHandler.handleLeaveRoom(socket);
    });
    
    // WebRTC 信令消息
    socket.on(MESSAGE_TYPES.OFFER, (data) => {
        MessageHandler.handleOffer(socket, data);
    });
    
    socket.on(MESSAGE_TYPES.ANSWER, (data) => {
        MessageHandler.handleAnswer(socket, data);
    });
    
    socket.on(MESSAGE_TYPES.ICE_CANDIDATE, (data) => {
        MessageHandler.handleIceCandidate(socket, data);
    });
    
    // 心跳
    socket.on(MESSAGE_TYPES.PING, () => {
        MessageHandler.handlePing(socket);
    });
    
    // 获取房间信息
    socket.on(MESSAGE_TYPES.ROOM_INFO, (data) => {
        MessageHandler.handleGetRoomInfo(socket, data);
    });
    
    // 断开连接处理
    socket.on('disconnect', (reason) => {
        clearTimeout(connectionTimeout);
        Logger.info(`Socket断开连接: ${socket.id}, 原因: ${reason}`);
        MessageHandler.handleLeaveRoom(socket);
    });
    
    // 错误处理
    socket.on('error', (error) => {
        Logger.error(`Socket错误: ${socket.id}`, error);
    });
});

// REST API 路由
app.get('/', (req, res) => {
    res.sendFile(path.join(__dirname, '../web-client/index.html'));
});

// 获取服务器状态
app.get('/api/status', (req, res) => {
    res.json({
        status: 'running',
        uptime: process.uptime(),
        rooms: rooms.size,
        connections: io.engine.clientsCount,
        timestamp: new Date().toISOString()
    });
});

// 获取房间列表
app.get('/api/rooms', (req, res) => {
    const roomList = Array.from(rooms.keys()).map(roomId => 
        RoomManager.getRoomInfo(roomId)
    );
    res.json({ rooms: roomList });
});

// 获取特定房间信息
app.get('/api/rooms/:roomId', (req, res) => {
    const { roomId } = req.params;
    const roomInfo = RoomManager.getRoomInfo(roomId);
    
    if (roomInfo) {
        res.json({ room: roomInfo });
    } else {
        res.status(404).json({ error: '房间不存在' });
    }
});

// 错误处理中间件
app.use((err, req, res, next) => {
    Logger.error('Express错误', err);
    res.status(500).json({ error: '服务器内部错误' });
});

// 定期清理过期房间
setInterval(() => {
    RoomManager.cleanupExpiredRooms();
}, 60 * 60 * 1000); // 每小时清理一次

// 优雅关闭
process.on('SIGTERM', () => {
    Logger.info('收到SIGTERM信号，开始优雅关闭...');
    server.close(() => {
        Logger.info('服务器已关闭');
        process.exit(0);
    });
});

process.on('SIGINT', () => {
    Logger.info('收到SIGINT信号，开始优雅关闭...');
    server.close(() => {
        Logger.info('服务器已关闭');
        process.exit(0);
    });
});

// 启动服务器
const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    Logger.info(`信令服务器启动成功`, {
        port: PORT,
        environment: process.env.NODE_ENV || 'development',
        pid: process.pid
    });
});

module.exports = { app, server, io }; 