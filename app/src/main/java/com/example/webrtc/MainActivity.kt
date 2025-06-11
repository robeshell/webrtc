package com.example.webrtc

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.example.webrtc.config.DynamicConfig
import com.example.webrtc.config.WebRTCConfig
import com.example.webrtc.manager.*
import com.example.webrtc.model.*
import com.example.webrtc.ui.theme.WebrtcTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.SessionDescription
import java.util.*
import android.widget.TextView
import android.view.View
import android.graphics.Canvas
import android.graphics.Paint
import com.example.webrtc.manager.ViewCapturer

/**
 * WebRTC屏幕投屏主界面
 * 
 * 功能：
 * 1. 管理信令连接状态
 * 2. 控制屏幕投屏开关
 * 3. 处理WebRTC连接
 * 4. 显示连接状态
 */
class MainActivity : ComponentActivity(), WebRTCEventCallback, SignalingCallback {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    // 管理器
    private lateinit var webRTCManager: WebRTCManager
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var socketIOSignalingManager: SocketIOSignalingManager
    private lateinit var signalingAdapter: SignalingAdapter
    
    // 配置
    private val config = DynamicConfig()
    
    // 状态
    private var connectionState by mutableStateOf(ConnectionState.DISCONNECTED)
    private var screenShareState by mutableStateOf(ScreenShareState.IDLE)
    private var signalingState by mutableStateOf(SignalingState.DISCONNECTED)
    private var roomId by mutableStateOf("DEMO_ROOM")
    private var userId by mutableStateOf("user_${UUID.randomUUID().toString().take(8)}")
    private var isScreenSharing by mutableStateOf(false)
    private var errorMessage by mutableStateOf("")
    
    // 远程用户
    private var remoteUserId by mutableStateOf("")
    
    // 新增：投屏模式
    private var captureMode by mutableStateOf(CaptureMode.APP_CONTENT) // 默认只录制app内容
    
    // 投屏内容View
    private var screenContentView: TextView? = null
    
    // 权限请求后的自动处理标志
    private var shouldStartAfterPermission by mutableStateOf(false)
    
    // 投屏内容更新时间
    private var contentUpdateTime by mutableStateOf(System.currentTimeMillis())
    
    // 服务器地址配置
    private var serverUrl by mutableStateOf("192.168.31.121:3000")
    private var isEditingServer by mutableStateOf(false)
    
    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.i(TAG, "所有权限已获取")
        } else {
            Log.w(TAG, "权限被拒绝")
            errorMessage = "需要相关权限才能使用屏幕投屏功能"
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化配置
        updateConfigForCaptureMode()
        
        initializeManagers()
        observeStates()
        
        setContent {
            WebrtcTheme {
                MainScreen()
            }
        }
        
        // 使用配置的服务器地址连接
        lifecycleScope.launch {
            val fullUrl = "http://$serverUrl"
            socketIOSignalingManager.connect(fullUrl)
            Log.i(TAG, "连接到服务器: $fullUrl")
        }
        
        // 启动内容更新定时器
        startContentUpdateTimer()
        
        // 请求权限
        requestPermissions()
    }
    
    /**
     * 请求必要权限
     */
    private fun requestPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.ACCESS_NETWORK_STATE,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.WAKE_LOCK
        )
        
        // Android 13+ 需要通知权限
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        
        // 可选的音频权限
        permissions.add(android.Manifest.permission.RECORD_AUDIO)
        
        // 使用标准权限请求
        permissionLauncher.launch(permissions.toTypedArray())
    }
    
    /**
     * 初始化管理器
     */
    private fun initializeManagers() {
        // 初始化屏幕捕获管理器
        screenCaptureManager = ScreenCaptureManager(this)
        
        // 初始化WebRTC管理器
        webRTCManager = WebRTCManager(this, config)
        webRTCManager.setEventCallback(this)
        
        // 初始化Socket.IO信令管理器
        signalingAdapter = SignalingAdapter(this)
        socketIOSignalingManager = SocketIOSignalingManager(signalingAdapter)
        
        // 监听状态变化
        lifecycleScope.launch {
            webRTCManager.connectionState.collectLatest { state ->
                connectionState = state
            }
        }
        
        lifecycleScope.launch {
            screenCaptureManager.screenShareState.collectLatest { state ->
                Log.d(TAG, "屏幕捕获状态变化: $state")
                screenShareState = state
                isScreenSharing = state == ScreenShareState.SHARING
                
                // 权限获取成功后自动继续（只在全屏模式）
                if (state == ScreenShareState.PREPARING && shouldStartAfterPermission && captureMode == CaptureMode.FULL_SCREEN) {
                    shouldStartAfterPermission = false
                    Log.i(TAG, "全屏权限获取成功，自动继续投屏流程...")
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(500) // 稍等一下确保状态稳定
                        continueScreenShare()
                    }
                }
            }
        }
        
        lifecycleScope.launch {
            socketIOSignalingManager.signalingState.collectLatest { state ->
                signalingState = state
            }
        }
        
        // 初始化WebRTC
        lifecycleScope.launch {
            val success = webRTCManager.initialize()
            if (!success) {
                errorMessage = "WebRTC初始化失败"
            }
        }
    }
    
    /**
     * 观察状态变化（由initializeManagers中的状态观察处理）
     */
    private fun observeStates() {
        // 状态观察已在initializeManagers中处理
    }
    
    /**
     * 启动内容更新定时器
     */
    private fun startContentUpdateTimer() {
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000) // 每秒更新一次
                contentUpdateTime = System.currentTimeMillis()
                updateScreenContentView()
            }
        }
    }
    
    /**
     * 更新投屏内容View
     */
    private fun updateScreenContentView() {
        screenContentView?.post {
            screenContentView?.text = buildString {
                appendLine("🎥 实时投屏内容区域 🎥")
                appendLine()
                appendLine("当前时间: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(contentUpdateTime))}")
                appendLine()
                appendLine("房间ID: $roomId")
                appendLine("用户ID: $userId")
                if (remoteUserId.isNotEmpty()) {
                    appendLine("连接用户: $remoteUserId")
                }
                appendLine()
                appendLine("连接状态:")
                appendLine("• 信令服务器: ${getSignalingStateText()}")
                appendLine("• WebRTC连接: ${getConnectionStateText()}")
                appendLine("• 投屏状态: ${getScreenShareStateText()}")
                appendLine()
                appendLine("投屏模式: ${if (captureMode == CaptureMode.APP_CONTENT) "仅App内容" else "整个屏幕"}")
                appendLine()
                appendLine("📱 这个区域的内容会实时投屏到Web端")
                appendLine("💡 内容每秒自动更新，可看到动态效果")
            }
        }
    }
    
    @Composable
    fun MainScreen() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 标题区域
                HeaderSection()
                
                // 状态区域
                StatusSection()
                
                // 服务器配置区域
                ServerConfigSection()
                
                // 投屏模式选择
                CaptureModeSection()
                
                // 投屏内容预览区域（只在app内容模式下显示）
                if (captureMode == CaptureMode.APP_CONTENT) {
                    ScreenContentSection()
                }
                
                // 控制区域
                ControlSection()
                
                // 房间信息区域
                RoomSection()
                
                // 错误信息
                if (errorMessage.isNotEmpty()) {
                    ErrorSection()
                }
                
                // 底部间距
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
    
    @Composable
    fun HeaderSection() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 图标背景
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            androidx.compose.ui.graphics.Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                ),
                                radius = 80f
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ScreenShare,
                        contentDescription = "屏幕投屏",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = "Android 屏幕投屏",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "基于WebRTC的实时屏幕共享",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
    
    @Composable
    fun StatusSection() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NetworkCheck,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "连接状态",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatusItem("信令服务器", getSignalingStateText(), getSignalingStateColor())
                    StatusItem("WebRTC连接", getConnectionStateText(), getConnectionStateColor())
                    StatusItem("屏幕共享", getScreenShareStateText(), getScreenShareStateColor())
                }
                
                // 添加详细状态说明
                when (screenShareState) {
                    ScreenShareState.PERMISSION_REQUIRED -> {
                        Spacer(modifier = Modifier.height(16.dp))
                        StatusDetailCard(
                            "• 前台服务已启动，请授权屏幕录制权限...",
                            MaterialTheme.colorScheme.secondary
                        )
                    }
                    ScreenShareState.PREPARING -> {
                        Spacer(modifier = Modifier.height(16.dp))
                        StatusDetailCard(
                            if (captureMode == CaptureMode.FULL_SCREEN) {
                                "• 权限已获取，正在创建屏幕捕获器..."
                            } else {
                                "• 正在初始化App内容捕获..."
                            },
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    ScreenShareState.SHARING -> {
                        Spacer(modifier = Modifier.height(16.dp))
                        StatusDetailCard(
                            "• 投屏进行中 - ${if (captureMode == CaptureMode.FULL_SCREEN) "超高画质(1920×1080@30fps)" else "优化模式(1024×576@20fps)"}",
                            MaterialTheme.colorScheme.primary
                        )
                    }
                    ScreenShareState.ERROR -> {
                        Spacer(modifier = Modifier.height(16.dp))
                        StatusDetailCard(
                            "• 投屏出现错误，请检查权限或重试",
                            MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {}
                }
            }
        }
    }
    
    @Composable
    fun StatusDetailCard(text: String, color: ComposeColor) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = color.copy(alpha = 0.1f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                color = color,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
    
    @Composable
    fun StatusItem(label: String, status: String, color: ComposeColor) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(color)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = status,
                        fontSize = 15.sp,
                        color = color,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
    
    @Composable
    fun ServerConfigSection() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "服务器配置",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    FloatingActionButton(
                        onClick = { 
                            if (isEditingServer) {
                                // 确认修改，重新连接服务器
                                isEditingServer = false
                                lifecycleScope.launch {
                                    try {
                                        Log.i(TAG, "确认服务器地址修改，准备重新连接: $serverUrl")
                                        socketIOSignalingManager.disconnect()
                                        kotlinx.coroutines.delay(1000)
                                        val fullUrl = "http://$serverUrl"
                                        Log.i(TAG, "开始连接到新服务器: $fullUrl")
                                        socketIOSignalingManager.connect(fullUrl)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "重新连接服务器失败", e)
                                        errorMessage = "连接服务器失败: ${e.message}"
                                    }
                                }
                            } else {
                                // 进入编辑模式
                                isEditingServer = true
                            }
                        },
                        modifier = Modifier.size(48.dp),
                        containerColor = if (isEditingServer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (isEditingServer) Icons.Default.Check else Icons.Default.Edit,
                            contentDescription = if (isEditingServer) "确认并重连" else "编辑",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                if (isEditingServer) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("服务器地址:端口") },
                        placeholder = { Text("例如: 192.168.1.100:3000") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        ),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    isEditingServer = false
                                    // 重新连接服务器
                                    lifecycleScope.launch {
                                        try {
                                            Log.i(TAG, "准备重新连接服务器，当前地址: $serverUrl")
                                            socketIOSignalingManager.disconnect()
                                            kotlinx.coroutines.delay(1000)
                                            val fullUrl = "http://$serverUrl"
                                            Log.i(TAG, "开始连接到新服务器: $fullUrl")
                                            socketIOSignalingManager.connect(fullUrl)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "重新连接服务器失败", e)
                                            errorMessage = "连接服务器失败: ${e.message}"
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Refresh, "重新连接")
                            }
                        }
                    )
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "当前服务器",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "http://$serverUrl",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(getSignalingStateColor().copy(alpha = 0.1f))
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = getSignalingStateText(),
                                        fontSize = 12.sp,
                                        color = getSignalingStateColor(),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "💻 Web客户端地址: http://$serverUrl",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(12.dp)
                )
            }
        }
    }
    
    @Composable
    fun CaptureModeSection() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "投屏模式",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 只投屏app内容
                    FilterChip(
                        onClick = { 
                            captureMode = CaptureMode.APP_CONTENT
                            updateConfigForCaptureMode()
                        },
                        label = { 
                            Text(
                                "仅App内容",
                                fontWeight = FontWeight.Medium
                            ) 
                        },
                        selected = captureMode == CaptureMode.APP_CONTENT,
                        modifier = Modifier.weight(1f),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Apps,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    
                    // 投屏整个屏幕
                    FilterChip(
                        onClick = { 
                            captureMode = CaptureMode.FULL_SCREEN
                            updateConfigForCaptureMode()
                        },
                        label = { 
                            Text(
                                "整个屏幕",
                                fontWeight = FontWeight.Medium
                            ) 
                        },
                        selected = captureMode == CaptureMode.FULL_SCREEN,
                        modifier = Modifier.weight(1f),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.FullscreenExit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = if (captureMode == CaptureMode.APP_CONTENT) 
                                "📱 App内容模式 - 优化性能" 
                            else 
                                "🎬 全屏模式 - 超高画质",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (captureMode == CaptureMode.APP_CONTENT) 
                                "1024×576, 20fps, 1.5Mbps" 
                            else 
                                "1920×1080, 30fps, 6Mbps",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
                
                // 添加权限选择提示
                if (captureMode == CaptureMode.FULL_SCREEN) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "权限选择提示",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "如果系统询问录制范围，请选择整个屏幕以获得最佳效果",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
    
    @Composable
    fun ScreenContentSection() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Preview,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "投屏内容预览",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                // 投屏内容区域
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // 使用AndroidView来创建一个可以被录制的View
                        AndroidView(
                            factory = { context ->
                                TextView(context).apply {
                                    text = "🎥 实时投屏内容区域 🎥\n\n正在初始化..."
                                    textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                                    textSize = 14f
                                    setTextColor(android.graphics.Color.BLACK)
                                    setPadding(24, 24, 24, 24)
                                    background = null
                                    screenContentView = this
                                    // 立即更新一次内容
                                    post { updateScreenContentView() }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Text(
                        text = "💡 只有上方这个区域会被投屏到Web端",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
    
    @Composable
    fun ControlSection() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 主要控制按钮
                Button(
                    onClick = { toggleScreenShare() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    enabled = signalingState == SignalingState.CONNECTED && screenShareState != ScreenShareState.PREPARING,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            screenShareState == ScreenShareState.PREPARING -> MaterialTheme.colorScheme.secondary
                            isScreenSharing -> MaterialTheme.colorScheme.error 
                            else -> MaterialTheme.colorScheme.primary
                        }
                    ),
                    shape = RoundedCornerShape(16.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = when {
                                screenShareState == ScreenShareState.PREPARING -> Icons.Default.HourglassEmpty
                                isScreenSharing -> Icons.Default.Stop 
                                else -> Icons.Default.PlayArrow
                            },
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = when {
                                screenShareState == ScreenShareState.PREPARING -> "正在准备..."
                                screenShareState == ScreenShareState.PERMISSION_REQUIRED -> "需要权限"
                                isScreenSharing -> "停止投屏"
                                else -> "开始投屏"
                            },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                
                // 连接/断开信令服务器
                OutlinedButton(
                    onClick = { toggleSignalingConnection() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    border = BorderStroke(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (signalingState == SignalingState.CONNECTED) 
                                Icons.Default.CloudOff 
                            else 
                                Icons.Default.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (signalingState == SignalingState.CONNECTED) 
                                "断开服务器" 
                            else 
                                "连接服务器",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
    
    @Composable
    fun RoomSection() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MeetingRoom,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "房间信息",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedTextField(
                        value = roomId,
                        onValueChange = { roomId = it },
                        label = { Text("房间ID") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isScreenSharing && signalingState == SignalingState.CONNECTED,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        ),
                        trailingIcon = {
                            IconButton(
                                onClick = { generateRandomRoomId() }
                            ) {
                                Icon(Icons.Default.Refresh, "生成随机房间ID")
                            }
                        }
                    )
                    
                    OutlinedTextField(
                        value = userId,
                        onValueChange = { userId = it },
                        label = { Text("用户ID") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = false,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    )
                    
                    if (remoteUserId.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "远程用户已连接",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                    Text(
                                        text = remoteUserId,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ErrorSection() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "错误",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(16.dp))
                IconButton(
                    onClick = { errorMessage = "" },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        "关闭",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
    
    /**
     * 切换屏幕投屏状态
     */
    private fun toggleScreenShare() {
        if (isScreenSharing) {
            stopScreenShare()
        } else {
            startScreenShare()
        }
    }
    
    /**
     * 开始屏幕投屏
     */
    private fun startScreenShare() {
        if (roomId.isBlank()) {
            errorMessage = "请输入房间ID"
            return
        }
        
        // 更新配置参数根据投屏模式
        updateConfigForCaptureMode()
        
        // 设置权限获取后自动继续的标志
        shouldStartAfterPermission = true
        
        lifecycleScope.launch {
            try {
                Log.i(TAG, "正在开始屏幕投屏...")
                
                // 首先停止任何现有的投屏
                if (isScreenSharing) {
                    Log.d(TAG, "停止现有的投屏会话")
                    stopScreenShare()
                    kotlinx.coroutines.delay(500) // 等待清理完成
                }
                
                if (captureMode == CaptureMode.APP_CONTENT) {
                    // App内容模式：直接开始投屏，不需要系统权限
                    Log.i(TAG, "使用App内容模式，直接开始投屏...")
                    shouldStartAfterPermission = false
                    continueScreenShare()
                } else {
                    // 全屏模式：需要请求系统权限
                    Log.i(TAG, "使用全屏模式，请求屏幕录制权限...")
                    screenCaptureManager.requestScreenCapturePermission(this@MainActivity)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "开始屏幕投屏失败", e)
                errorMessage = "开始屏幕投屏失败: ${e.message}"
                shouldStartAfterPermission = false
            }
        }
    }
    
    /**
     * 继续屏幕投屏流程（权限获取后调用）
     */
    private suspend fun continueScreenShare() {
        try {
            Log.i(TAG, "开始创建屏幕投屏，模式: ${if (captureMode == CaptureMode.APP_CONTENT) "App内容" else "全屏"}")
            
            val screenCapturer = if (captureMode == CaptureMode.APP_CONTENT) {
                // 创建View捕获器
                Log.d(TAG, "创建View捕获器...")
                createViewCapturer()
            } else {
                // 创建屏幕捕获器
                Log.d(TAG, "创建屏幕捕获器...")
                
                // 检查权限状态
                if (!screenCaptureManager.hasScreenCapturePermission()) {
                    Log.e(TAG, "屏幕录制权限未获取")
                    errorMessage = "屏幕录制权限未获取，请重新授权"
                    shouldStartAfterPermission = false
                    screenCaptureManager.setScreenShareState(ScreenShareState.ERROR)
                    return
                }
                
                screenCaptureManager.createScreenCapturer()
            }
            
            if (screenCapturer == null) {
                val errorMsg = if (captureMode == CaptureMode.APP_CONTENT) {
                    "创建View捕获器失败，请检查投屏内容区域"
                } else {
                    "创建屏幕捕获器失败，权限可能已失效"
                }
                Log.e(TAG, errorMsg)
                errorMessage = errorMsg
                shouldStartAfterPermission = false
                screenCaptureManager.setScreenShareState(ScreenShareState.ERROR)
                return
            }
            
            Log.i(TAG, "屏幕捕获器创建成功")
            
            // 设置屏幕捕获器到WebRTC
            webRTCManager.setScreenCapturer(screenCapturer)
            
            // 创建PeerConnection
            if (!webRTCManager.createPeerConnection()) {
                errorMessage = "创建WebRTC连接失败"
                shouldStartAfterPermission = false
                return
            }
            
            // 创建本地媒体流
            if (!webRTCManager.createLocalMediaStream()) {
                errorMessage = "创建本地媒体流失败"
                shouldStartAfterPermission = false
                return
            }
            
            // 加入房间
            socketIOSignalingManager.joinRoom(roomId, userId, "broadcaster")
            
            // 确保状态更新为SHARING
            if (captureMode == CaptureMode.APP_CONTENT) {
                screenCaptureManager.setScreenShareState(ScreenShareState.SHARING)
            } else {
                // 全屏模式由ScreenCaptureManager自动管理状态
                Log.d(TAG, "全屏模式，等待ScreenCaptureManager更新状态...")
            }
            
            Log.i(TAG, "屏幕投屏创建成功，等待观看方连接...")
            
        } catch (e: Exception) {
            Log.e(TAG, "创建屏幕投屏失败", e)
            errorMessage = "创建屏幕投屏失败: ${e.message}"
            shouldStartAfterPermission = false
            // 重置状态
            screenCaptureManager.setScreenShareState(ScreenShareState.ERROR)
        }
    }
    
    /**
     * 创建View捕获器（用于只投屏app内容）
     */
    private fun createViewCapturer(): org.webrtc.VideoCapturer? {
        val contentView = screenContentView
        if (contentView == null) {
            Log.e(TAG, "投屏内容View未初始化")
            return null
        }
        
        // 创建SurfaceTexture录制器
        return ViewCapturer(contentView)
    }
    
    /**
     * 停止屏幕投屏
     */
    private fun stopScreenShare() {
        lifecycleScope.launch {
            try {
                Log.i(TAG, "开始停止屏幕投屏...")
                
                // 重置标志
                shouldStartAfterPermission = false
                
                // 停止屏幕捕获
                screenCaptureManager.stopScreenCapture()
                
                // 停止WebRTC
                webRTCManager.stopScreenCapture()
                
                // 离开房间
                socketIOSignalingManager.leaveRoom()
                
                // 确保状态重置
                screenCaptureManager.setScreenShareState(ScreenShareState.STOPPED)
                
                Log.i(TAG, "屏幕投屏已停止")
                
            } catch (e: Exception) {
                Log.e(TAG, "停止屏幕投屏失败", e)
                errorMessage = "停止屏幕投屏失败: ${e.message}"
                // 强制重置状态
                screenCaptureManager.setScreenShareState(ScreenShareState.ERROR)
            }
        }
    }
    
    /**
     * 切换信令连接状态
     */
    private fun toggleSignalingConnection() {
        if (signalingState == SignalingState.CONNECTED) {
            Log.i(TAG, "手动断开信令服务器连接")
            socketIOSignalingManager.disconnect()
        } else {
            val fullUrl = "http://$serverUrl"
            Log.i(TAG, "手动连接到信令服务器: $fullUrl")
            socketIOSignalingManager.connect(fullUrl)
        }
    }
    
    /**
     * 生成随机房间ID
     */
    private fun generateRandomRoomId() {
        roomId = "room_${UUID.randomUUID().toString().take(8)}"
    }
    
    /**
     * 处理屏幕录制权限结果
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")
        
        if (screenCaptureManager.handleScreenCapturePermissionResult(requestCode, resultCode, data)) {
            // 权限获取成功，状态会自动变化，触发自动继续逻辑
            Log.i(TAG, "屏幕录制权限获取成功，等待状态变化自动继续...")
        } else {
            // 权限获取失败
            Log.w(TAG, "屏幕录制权限获取失败")
            errorMessage = "屏幕录制权限获取失败，请重试"
            shouldStartAfterPermission = false
        }
    }
    
    /**
     * 创建屏幕投屏
     */
    private suspend fun createScreenShare() {
        try {
            // 创建屏幕捕获器
            val screenCapturer = screenCaptureManager.createScreenCapturer()
            if (screenCapturer == null) {
                errorMessage = "创建屏幕捕获器失败"
                return
            }
            
            // 设置屏幕捕获器到WebRTC
            webRTCManager.setScreenCapturer(screenCapturer)
            
            // 创建PeerConnection
            if (!webRTCManager.createPeerConnection()) {
                errorMessage = "创建WebRTC连接失败"
                return
            }
            
            // 创建本地媒体流
            if (!webRTCManager.createLocalMediaStream()) {
                errorMessage = "创建本地媒体流失败"
                return
            }
            
            // 加入房间
            socketIOSignalingManager.joinRoom(roomId, userId, "broadcaster")
            
            Log.i(TAG, "屏幕投屏创建成功，等待观看方连接...")
            
        } catch (e: Exception) {
            Log.e(TAG, "创建屏幕投屏失败", e)
            errorMessage = "创建屏幕投屏失败: ${e.message}"
        }
    }
    
    // 状态转换辅助方法
    private fun getSignalingStateText(): String = when (signalingState) {
        SignalingState.DISCONNECTED -> "未连接"
        SignalingState.CONNECTING -> "连接中"
        SignalingState.CONNECTED -> "已连接"
        SignalingState.RECONNECTING -> "重连中"
        SignalingState.ERROR -> "连接错误"
    }
    
    private fun getSignalingStateColor(): ComposeColor = when (signalingState) {
        SignalingState.CONNECTED -> ComposeColor(0xFF4CAF50)
        SignalingState.CONNECTING, SignalingState.RECONNECTING -> ComposeColor(0xFFFF9800)
        SignalingState.ERROR -> ComposeColor(0xFFF44336)
        else -> ComposeColor(0xFF9E9E9E)
    }
    
    private fun getConnectionStateText(): String = when (connectionState) {
        ConnectionState.DISCONNECTED -> "未连接"
        ConnectionState.CONNECTING -> "连接中"
        ConnectionState.CONNECTED -> "已连接"
        ConnectionState.RECONNECTING -> "重连中"
        ConnectionState.FAILED -> "连接失败"
        ConnectionState.CLOSED -> "已关闭"
    }
    
    private fun getConnectionStateColor(): ComposeColor = when (connectionState) {
        ConnectionState.CONNECTED -> ComposeColor(0xFF4CAF50)
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> ComposeColor(0xFFFF9800)
        ConnectionState.FAILED -> ComposeColor(0xFFF44336)
        else -> ComposeColor(0xFF9E9E9E)
    }
    
    private fun getScreenShareStateText(): String = when (screenShareState) {
        ScreenShareState.IDLE -> "空闲"
        ScreenShareState.PERMISSION_REQUIRED -> "需要权限"
        ScreenShareState.PREPARING -> "准备中"
        ScreenShareState.SHARING -> "投屏中"
        ScreenShareState.STOPPED -> "已停止"
        ScreenShareState.ERROR -> "错误"
    }
    
    private fun getScreenShareStateColor(): ComposeColor = when (screenShareState) {
        ScreenShareState.SHARING -> ComposeColor(0xFF4CAF50)
        ScreenShareState.PREPARING -> ComposeColor(0xFFFF9800)
        ScreenShareState.ERROR -> ComposeColor(0xFFF44336)
        else -> ComposeColor(0xFF9E9E9E)
    }
    
    // WebRTC事件回调
    override fun onConnectionStateChanged(state: ConnectionState) {
        Log.d(TAG, "WebRTC连接状态变化: $state")
    }
    
    override fun onIceCandidate(iceCandidate: IceCandidate) {
        // 发送ICE候选到远程用户
        if (remoteUserId.isNotEmpty()) {
            socketIOSignalingManager.sendIceCandidate(
                iceCandidate.sdp,
                iceCandidate.sdpMid ?: "",
                iceCandidate.sdpMLineIndex,
                remoteUserId
            )
        }
    }
    
    override fun onRemoteStreamAdded(mediaStream: MediaStream) {
        Log.d(TAG, "收到远程媒体流")
    }
    
    override fun onRemoteStreamRemoved(mediaStream: MediaStream) {
        Log.d(TAG, "远程媒体流被移除")
    }
    
    override fun onError(error: String) {
        errorMessage = error
    }
    
    // 信令事件回调
    override fun onSignalingConnected() {
        Log.i(TAG, "信令服务器连接成功")
    }
    
    override fun onSignalingDisconnected() {
        Log.i(TAG, "信令服务器连接断开")
    }
    
    override fun onSignalingError(error: String) {
        errorMessage = "信令错误: $error"
    }
    
    override fun onRoomJoined(roomId: String) {
        Log.i(TAG, "加入房间成功: $roomId")
    }
    
    override fun onRoomLeft(roomId: String) {
        Log.i(TAG, "离开房间: $roomId")
    }
    
    override fun onUserJoined(userId: String) {
        Log.i(TAG, "用户加入: $userId")
        remoteUserId = userId
        
        // 作为投屏方，等待观看方发送Offer
        Log.d(TAG, "等待观看方发起连接...")
    }
    
    override fun onUserLeft(userId: String) {
        Log.i(TAG, "用户离开: $userId")
        if (remoteUserId == userId) {
            remoteUserId = ""
        }
    }
    
    override fun onOfferReceived(sdp: String, fromUserId: String) {
        Log.d(TAG, "收到Offer: $fromUserId")
        remoteUserId = fromUserId
        
        // 接收到观看方的Offer，需要创建Answer
        lifecycleScope.launch {
            try {
                val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
                val answer = webRTCManager.createAnswer(offer)
                if (answer != null) {
                    webRTCManager.setLocalDescription(answer)
                    socketIOSignalingManager.sendAnswer(answer.description, fromUserId)
                    Log.d(TAG, "Answer已发送给: $fromUserId")
                } else {
                    Log.e(TAG, "创建Answer失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理Offer失败", e)
            }
        }
    }
    
    override fun onAnswerReceived(sdp: String, fromUserId: String) {
        Log.d(TAG, "收到Answer: $fromUserId")
        lifecycleScope.launch {
            val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
            webRTCManager.setRemoteDescription(answer)
        }
    }
    
    override fun onIceCandidateReceived(candidate: String, sdpMid: String, sdpMLineIndex: Int, fromUserId: String) {
        Log.d(TAG, "收到ICE候选: $fromUserId")
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        webRTCManager.addIceCandidate(iceCandidate)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // 清理资源
        if (::webRTCManager.isInitialized) {
            webRTCManager.close()
        }
        
        if (::screenCaptureManager.isInitialized) {
            screenCaptureManager.release()
        }
        
        if (::socketIOSignalingManager.isInitialized) {
            socketIOSignalingManager.cleanup()
        }
    }
    
    /**
     * 更新配置参数根据投屏模式
     */
    private fun updateConfigForCaptureMode() {
        if (captureMode == CaptureMode.FULL_SCREEN) {
            // 全屏模式：高画质配置
            config.videoWidth = WebRTCConfig.Video.FULLSCREEN_WIDTH
            config.videoHeight = WebRTCConfig.Video.FULLSCREEN_HEIGHT
            config.videoFps = WebRTCConfig.Video.FULLSCREEN_FPS
            config.videoBitrate = WebRTCConfig.Video.FULLSCREEN_BITRATE
            Log.i(TAG, "🎬 全屏超高画质模式: ${config.videoWidth}×${config.videoHeight}@${config.videoFps}fps, ${config.videoBitrate}kbps (${config.videoBitrate/1000f}Mbps)")
        } else {
            // App内容模式：优化配置
            config.videoWidth = WebRTCConfig.Video.DEFAULT_WIDTH
            config.videoHeight = WebRTCConfig.Video.DEFAULT_HEIGHT
            config.videoFps = WebRTCConfig.Video.DEFAULT_FPS
            config.videoBitrate = WebRTCConfig.Video.DEFAULT_BITRATE
            Log.i(TAG, "📱 App内容优化模式: ${config.videoWidth}×${config.videoHeight}@${config.videoFps}fps, ${config.videoBitrate}kbps (${config.videoBitrate/1000f}Mbps)")
        }
    }
}

/**
 * 投屏模式枚举
 */
enum class CaptureMode {
    APP_CONTENT,    // 只投屏app内容
    FULL_SCREEN     // 投屏整个屏幕
}