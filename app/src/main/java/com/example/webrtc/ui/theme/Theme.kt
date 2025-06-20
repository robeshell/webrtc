package com.example.webrtc.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

// 统一的亮色主题 - 所有设备都使用相同配色
private val UnifiedLightColorScheme = lightColorScheme(
    primary = AppBlue,
    onPrimary = AppSurface,
    primaryContainer = AppLightBlue,
    onPrimaryContainer = AppBlueVariant,
    
    secondary = AppBlueVariant,
    onSecondary = AppSurface,
    secondaryContainer = AppLightBlue,
    onSecondaryContainer = AppBlue,
    
    tertiary = AppGreen,
    onTertiary = AppSurface,
    
    background = AppBackground,
    onBackground = AppOnBackground,
    
    surface = AppSurface,
    onSurface = AppOnSurface,
    surfaceVariant = AppBackground,
    onSurfaceVariant = AppOnSurface,
    
    error = AppRed,
    onError = AppSurface,
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = AppRed,
    
    outline = AppGray,
    outlineVariant = AppGray.copy(alpha = 0.5f)
)

// 统一的深色主题 - 为深色模式提供一致体验
private val UnifiedDarkColorScheme = darkColorScheme(
    primary = AppBlue,
    onPrimary = Color(0xFF1A1A1A),
    primaryContainer = AppBlueVariant,
    onPrimaryContainer = AppLightBlue,
    
    secondary = AppLightBlue,
    onSecondary = Color(0xFF1A1A1A),
    secondaryContainer = AppBlueVariant,
    onSecondaryContainer = AppLightBlue,
    
    tertiary = AppGreen,
    onTertiary = Color(0xFF1A1A1A),
    
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFE0E0E0),
    
    error = AppRed,
    onError = Color(0xFF1A1A1A),
    errorContainer = Color(0xFF4A0E0E),
    onErrorContainer = Color(0xFFFFB4B4),
    
    outline = Color(0xFF757575),
    outlineVariant = Color(0xFF424242)
)

@Composable
fun CastHelperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 强制禁用动态颜色，确保一致性
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // 始终使用我们自定义的颜色方案，不依赖系统主题
    val colorScheme = if (darkTheme) {
        UnifiedDarkColorScheme
    } else {
        UnifiedLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}