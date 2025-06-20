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
import com.example.webrtc.ui.theme.CastHelperTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            CastHelperTheme {
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
                // Logo区域 - 遵循Google设计规范，增加适当的空白空间
                Card(
                    modifier = Modifier
                        .size(100.dp) // 减小尺寸，增加呼吸感
                        .scale(logoScale),
                    shape = CircleShape,
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.95f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp), // 增加内边距，符合设计规范
                        contentAlignment = Alignment.Center
                    ) {
                        // 使用自定义logo
                        Icon(
                            painter = painterResource(id = R.drawable.ic_app_logo),
                            contentDescription = "App Logo",
                            modifier = Modifier.size(60.dp), // 减小logo尺寸，增加空白空间
                            tint = Color.Unspecified // 保持原始颜色
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp)) // 减少间距，更紧凑
                
                // 应用名称 - 更简洁的设计
                Text(
                    text = "投屏助手",
                    fontSize = 24.sp, // 减小字体，更优雅
                    fontWeight = FontWeight.SemiBold, // 使用SemiBold替代Bold
                    color = Color.White,
                    modifier = Modifier.alpha(alpha),
                    letterSpacing = 1.sp // 增加字符间距，更现代
                )
                
                Spacer(modifier = Modifier.height(6.dp)) // 减少间距
                
                // 副标题 - 更简洁的描述
                Text(
                    text = "高清实时投屏",
                    fontSize = 14.sp, // 减小字体
                    color = Color.White.copy(alpha = 0.7f), // 降低透明度
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(alpha),
                    letterSpacing = 0.5.sp
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