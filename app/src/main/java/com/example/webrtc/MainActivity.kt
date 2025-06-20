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
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.delay

/**
 * WebRTC屏幕投屏主界面
 * 
 * 功能：
 * 1. 管理信令连接状态
 * 2. 控制屏幕投屏开关
 * 3. 处理WebRTC连接
 * 4. 显示连接状态
 */
class MainActivity : ComponentActivity(), WebRTCEventCallback, SocketIOSignalingManager.SignalingCallback {
    
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
    
    // HTTP客户端
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
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
            
            // 连接成功后自动获取房间
            delay(2000) // 等待连接稳定
            fetchAvailableRoom()
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
                CaptureSection()
                
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
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NetworkCheck,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "连接状态",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                // 只显示最重要的状态
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 服务器状态
                    StatusChip("服务器", getSignalingStateText(), getSignalingStateColor())
                    
                    // 投屏状态
                    StatusChip("投屏", getScreenShareStateText(), getScreenShareStateColor())
                }
            }
        }
    }
    
    @Composable
    fun StatusChip(label: String, status: String, color: ComposeColor) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = color.copy(alpha = 0.1f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$label: $status",
                    fontSize = 12.sp,
                    color = color,
                    fontWeight = FontWeight.Medium
                )
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
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "服务器配置",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                if (isEditingServer) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("服务器地址") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { 
                                    isEditingServer = false
                                    // 重新连接服务器
                                    lifecycleScope.launch {
                                        socketIOSignalingManager.disconnect()
                                        kotlinx.coroutines.delay(500)
                                        socketIOSignalingManager.connect("http://$serverUrl")
                                    }
                                }) {
                                    Icon(Icons.Default.Check, "确认")
                                }
                                IconButton(onClick = { isEditingServer = false }) {
                                    Icon(Icons.Default.Close, "取消")
                                }
                            }
                        }
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = serverUrl,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { isEditingServer = true }) {
                            Icon(
                                Icons.Default.Edit,
                                "编辑",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
    
    @Composable
    fun CaptureSection() {
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
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "投屏模式",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // App内容模式
                    FilterChip(
                        onClick = { captureMode = CaptureMode.APP_CONTENT },
                        label = { Text("App内容") },
                        selected = captureMode == CaptureMode.APP_CONTENT,
                        modifier = Modifier.weight(1f),
                        leadingIcon = {
                            Icon(
                                Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                    
                    // 全屏模式
                    FilterChip(
                        onClick = { captureMode = CaptureMode.FULL_SCREEN },
                        label = { Text("全屏") },
                        selected = captureMode == CaptureMode.FULL_SCREEN,
                        modifier = Modifier.weight(1f),
                        leadingIcon = {
                            Icon(
                                Icons.Default.Fullscreen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = if (captureMode == CaptureMode.APP_CONTENT) {
                        "只投屏当前应用内容，1080p超高清画质"
                    } else {
                        "投屏整个屏幕，需要系统权限"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
    
    @Composable
    fun ScreenContentSection() {
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
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Preview,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "投屏内容预览",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { context ->
                                TextView(context).apply {
                                    text = "这是投屏内容区域\n时间: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(contentUpdateTime))}"
                                    textSize = 16f
                                    setTextColor(Color.BLACK)
                                    gravity = android.view.Gravity.CENTER
                                    setBackgroundColor(Color.WHITE)
                                    setPadding(32, 32, 32, 32)
                                    screenContentView = this
                                }
                            },
                            update = { view ->
                                view.text = "这是投屏内容区域\n时间: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(contentUpdateTime))}"
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "此区域的内容将被投屏到观看端",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
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
                modifier = Modifier.padding(24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ControlCamera,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "投屏控制",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                // 主控制按钮
                Button(
                    onClick = { toggleScreenShare() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScreenSharing) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                ) {
                    Icon(
                        imageVector = if (isScreenSharing) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (isScreenSharing) "停止投屏" else "开始投屏",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 房间状态显示
                if (roomId.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.MeetingRoom,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "已获取房间",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = roomId,
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
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MeetingRoom,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "房间信息",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                // 房间ID
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "房间ID",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            text = roomId.ifEmpty { "未设置" },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    
                    Row {
                        // 刷新房间按钮
                        IconButton(
                            onClick = {
                                lifecycleScope.launch {
                                    fetchAvailableRoom()
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                "刷新房间",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        
                        // 复制房间ID按钮
                        IconButton(
                            onClick = {
                                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("房间ID", roomId)
                                clipboard.setPrimaryClip(clip)
                            }
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                "复制",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 用户ID
                Column {
                    Text(
                        text = "用户ID",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        text = userId,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                // 观看者信息
                if (remoteUserId.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "观看者已连接",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                Text(
                                    text = remoteUserId,
                                    fontSize = 13.sp,
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
                    // 防止重复调用
        if (isScreenSharing || shouldStartAfterPermission) {
            Log.w(TAG, "投屏已在进行中或等待权限，跳过重复调用")
            return
        }
        
        // 如果房间ID为空，自动获取可用房间
        if (roomId.isBlank()) {
            Log.i(TAG, "房间ID为空，自动获取可用房间...")
            lifecycleScope.launch {
                try {
                    fetchAvailableRoom()
                    kotlinx.coroutines.delay(1000)
                    if (roomId.isNotBlank()) {
                        startScreenShareInternal()
                    } else {
                        errorMessage = "无法获取可用房间，请检查服务器连接"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "自动获取房间失败", e)
                    errorMessage = "自动获取房间失败，请检查服务器连接"
                }
            }
            return
        }
        
        startScreenShareInternal()
    }
    
    /**
     * 内部投屏启动逻辑
     */
    private fun startScreenShareInternal() {
                    // 再次检查状态，防止并发调用
        if (isScreenSharing || shouldStartAfterPermission) {
            Log.w(TAG, "投屏状态检查：已在进行中，取消启动")
            return
        }
        
        updateConfigForCaptureMode()
        shouldStartAfterPermission = true
        
        lifecycleScope.launch {
            try {
                Log.i(TAG, "正在开始屏幕投屏，房间ID: $roomId")
                
                // 检查WebRTC初始化状态
                if (!webRTCManager.isInitialized.value) {
                    Log.w(TAG, "WebRTC未初始化，重新初始化...")
                    val success = webRTCManager.initialize()
                    if (!success) {
                        errorMessage = "WebRTC重新初始化失败"
                        shouldStartAfterPermission = false
                        return@launch
                    }
                }
                
                if (isScreenSharing) {
                    Log.d(TAG, "停止现有的投屏会话")
                    stopScreenShare()
                    kotlinx.coroutines.delay(500)
                }
                
                if (captureMode == CaptureMode.APP_CONTENT) {
                    Log.i(TAG, "使用App内容模式，直接开始投屏...")
                    shouldStartAfterPermission = false
                    continueScreenShare()
                } else {
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
                Log.d(TAG, "创建View捕获器...")
                createViewCapturer()
            } else {
                Log.d(TAG, "创建屏幕捕获器...")
                
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
            
            webRTCManager.setScreenCapturer(screenCapturer)
            
            if (!webRTCManager.createPeerConnection()) {
                errorMessage = "创建WebRTC连接失败"
                shouldStartAfterPermission = false
                return
            }
            
            if (!webRTCManager.createLocalMediaStream()) {
                errorMessage = "创建本地媒体流失败"
                shouldStartAfterPermission = false
                return
            }
            
            socketIOSignalingManager.joinRoom(roomId, userId, "broadcaster")
            
            if (captureMode == CaptureMode.APP_CONTENT) {
                screenCaptureManager.setScreenShareState(ScreenShareState.SHARING)
            } else {
                Log.d(TAG, "全屏模式，等待ScreenCaptureManager更新状态...")
            }
            
            Log.i(TAG, "屏幕投屏创建成功，等待观看方连接...")
            
        } catch (e: Exception) {
            Log.e(TAG, "创建屏幕投屏失败", e)
            errorMessage = "创建屏幕投屏失败: ${e.message}"
            shouldStartAfterPermission = false
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
        
        return ViewCapturer(contentView as View)
    }
    
    /**
     * 停止屏幕投屏
     */
    private fun stopScreenShare() {
        lifecycleScope.launch {
            try {
                Log.i(TAG, "开始停止屏幕投屏")
                
                // 防止重复停止
                if (!isScreenSharing && screenShareState == ScreenShareState.STOPPED) {
                    Log.w(TAG, "投屏已经停止，跳过重复操作")
                    return@launch
                }
                
                // 重置标志
                shouldStartAfterPermission = false
                
                // 1. 先更新UI状态，避免用户重复点击
                screenCaptureManager.setScreenShareState(ScreenShareState.STOPPED)
                
                // 2. 离开房间（先断开信令）
                try {
                    socketIOSignalingManager.leaveRoom()
                    Log.d(TAG, "已离开信令房间")
                } catch (e: Exception) {
                    Log.w(TAG, "离开房间异常: ${e.message}")
                }
                
                // 3. 停止屏幕捕获
                try {
                    screenCaptureManager.stopScreenCapture()
                    Log.d(TAG, "屏幕捕获已停止")
                } catch (e: Exception) {
                    Log.w(TAG, "停止屏幕捕获异常: ${e.message}")
                }
                
                // 4. 最后关闭WebRTC连接
                try {
                    webRTCManager.close()
                    Log.d(TAG, "WebRTC连接已关闭")
                } catch (e: Exception) {
                    Log.w(TAG, "关闭WebRTC异常: ${e.message}")
                }
                
                Log.i(TAG, "屏幕投屏已完全停止")
                
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
    
    /**
     * 🆕 从服务器获取可用房间号
     */
    private suspend fun fetchAvailableRoom() {
        try {
            val request = Request.Builder()
                .url("http://$serverUrl/api/available-room")
                .build()
            
            val response = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute()
            }
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                Log.d(TAG, "获取房间响应: $responseBody")
                if (!responseBody.isNullOrEmpty()) {
                    val json = JSONObject(responseBody)
                    val success = json.optBoolean("success", false)
                    if (success) {
                        val availableRoomId = json.optString("roomId")
                        if (availableRoomId.isNotEmpty()) {
                            roomId = availableRoomId
                            val waitingViewers = json.optInt("waitingViewers", 0)
                            val message = json.optString("message", "")
                            Log.i(TAG, "获取到可用房间: $roomId，等待观看者: $waitingViewers")
                            Log.i(TAG, "服务器消息: $message")
                        } else {
                            Log.w(TAG, "服务器返回空房间ID")
                        }
                    } else {
                        val message = json.optString("message", "没有可用房间")
                        Log.i(TAG, "服务器响应: $message")
                    }
                }
            } else {
                Log.w(TAG, "获取可用房间失败: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取可用房间异常", e)
        }
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
    
    override fun onUserJoined(userId: String, userType: String) {
        Log.i(TAG, "用户加入: $userId, 类型: $userType")
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
    
    override fun onOfferReceived(fromUserId: String, sdp: String) {
        Log.d(TAG, "收到Offer: $fromUserId")
        remoteUserId = fromUserId
        
        // 接收到观看方的Offer，需要创建Answer
        lifecycleScope.launch {
            try {
                val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
                Log.d(TAG, "开始创建Answer响应")
                val answer = webRTCManager.createAnswer(offer)
                if (answer != null) {
                    Log.d(TAG, "Answer创建成功，SDP长度: ${answer.description.length}")
                    val setResult = webRTCManager.setLocalDescription(answer)
                    if (setResult) {
                        socketIOSignalingManager.sendAnswer(answer.description, fromUserId)
                        Log.d(TAG, "Answer已发送给: $fromUserId")
                    } else {
                        Log.e(TAG, "设置本地描述失败")
                    }
                } else {
                    Log.e(TAG, "创建Answer失败 - answer为null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理Offer失败", e)
                errorMessage = "处理连接请求失败: ${e.message}"
            }
        }
    }
    
    override fun onAnswerReceived(fromUserId: String, sdp: String) {
        Log.d(TAG, "收到Answer: $fromUserId")
        lifecycleScope.launch {
            val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
            webRTCManager.setRemoteDescription(answer)
        }
    }
    
    override fun onIceCandidateReceived(fromUserId: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) {
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
        // 避免重复更新相同配置
        val newConfig = if (captureMode == CaptureMode.FULL_SCREEN) {
            // 全屏模式：高画质配置
            config.copy(
                videoWidth = WebRTCConfig.Video.FULLSCREEN_WIDTH,
                videoHeight = WebRTCConfig.Video.FULLSCREEN_HEIGHT,
                videoFps = WebRTCConfig.Video.FULLSCREEN_FPS,
                videoBitrate = WebRTCConfig.Video.FULLSCREEN_BITRATE
            )
        } else {
            // App内容模式：优化配置
            config.copy(
                videoWidth = WebRTCConfig.Video.DEFAULT_WIDTH,
                videoHeight = WebRTCConfig.Video.DEFAULT_HEIGHT,
                videoFps = WebRTCConfig.Video.DEFAULT_FPS,
                videoBitrate = WebRTCConfig.Video.DEFAULT_BITRATE
            )
        }
        
        // 只有当配置确实发生变化时才更新
        val currentConfig = config
        if (currentConfig.videoWidth != newConfig.videoWidth ||
            currentConfig.videoHeight != newConfig.videoHeight ||
            currentConfig.videoFps != newConfig.videoFps ||
            currentConfig.videoBitrate != newConfig.videoBitrate) {
            
            config.videoWidth = newConfig.videoWidth
            config.videoHeight = newConfig.videoHeight
            config.videoFps = newConfig.videoFps
            config.videoBitrate = newConfig.videoBitrate
            
            if (captureMode == CaptureMode.FULL_SCREEN) {
                Log.i(TAG, "全屏超高画质模式: ${config.videoWidth}×${config.videoHeight}@${config.videoFps}fps, ${config.videoBitrate}kbps")
            } else {
                Log.i(TAG, "App内容超高清模式: ${config.videoWidth}×${config.videoHeight}@${config.videoFps}fps, ${config.videoBitrate}kbps")
            }
            
            // 只有在WebRTC已初始化时才更新配置
            if (::webRTCManager.isInitialized && webRTCManager.isInitialized.value) {
                webRTCManager.updateConfig(config)
            } else {
                Log.d(TAG, "WebRTC未初始化，配置将在初始化时应用")
            }
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