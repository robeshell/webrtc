package com.example.webrtc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.webrtc.ui.theme.WebrtcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            WebrtcTheme {
                SplashScreen()
            }
        }
        
        // 3秒后跳转到主界面
        lifecycleScope.launch {
            delay(3000)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }
    
    @Composable
    fun SplashScreen() {
        // 动画状态
        val infiniteTransition = rememberInfiniteTransition(label = "splash")
        
        // Logo缩放动画
        val logoScale by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = EaseInOutCubic),
                repeatMode = RepeatMode.Reverse
            ),
            label = "logoScale"
        )
        
        // 透明度动画
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )
        
        // 渐变背景
        val gradientColors = listOf(
            Color(0xFF1976D2),
            Color(0xFF42A5F5),
            Color(0xFF64B5F6)
        )
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(gradientColors)
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Logo区域
                Card(
                    modifier = Modifier
                        .size(120.dp)
                        .scale(logoScale),
                    shape = CircleShape,
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.9f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        // 使用自定义logo
                        Icon(
                            painter = painterResource(id = R.drawable.ic_app_logo),
                            contentDescription = "App Logo",
                            modifier = Modifier.size(80.dp),
                            tint = Color.Unspecified // 保持原始颜色
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // 应用名称
                Text(
                    text = "WebRTC 投屏",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.alpha(alpha)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 副标题
                Text(
                    text = "高清实时屏幕共享",
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(alpha)
                )
                
                Spacer(modifier = Modifier.height(48.dp))
                
                // 加载指示器
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color.White,
                    strokeWidth = 3.dp
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "正在初始化...",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.alpha(alpha)
                )
            }
        }
    }
} 