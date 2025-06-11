@echo off
chcp 65001 >nul
echo ========================================
echo     WebRTC 三端连通性检查工具
echo ========================================
echo.

echo [1/5] 检查 Node.js 环境...
node --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ Node.js 未安装
    goto :error
) else (
    echo ✅ Node.js 版本: 
    node --version
)

echo.
echo [2/5] 检查服务器端口配置...
findstr "PORT.*3000" signaling-server\server.js >nul
if %errorlevel% equ 0 (
    echo ✅ 服务器配置端口: 3000
) else (
    echo ❌ 服务器端口配置错误
    goto :error
)

echo.
echo [3/5] 检查 Web 客户端配置...
findstr "localhost:3000" web-client\index.html >nul
if %errorlevel% equ 0 (
    echo ✅ Web 客户端默认服务器地址: ws://localhost:3000
) else (
    echo ❌ Web 客户端服务器地址配置错误
    goto :error
)

echo.
echo [4/5] 检查 Android 客户端配置...
findstr "localhost:3000" app\src\main\java\com\example\webrtc\config\WebRTCConfig.kt >nul
if %errorlevel% equ 0 (
    echo ✅ Android 客户端默认服务器地址: ws://localhost:3000
) else (
    echo ❌ Android 客户端服务器地址配置错误
    goto :error
)

echo.
echo [5/5] 测试服务器连接...
curl -s http://localhost:3000/api/status >nul 2>&1
if %errorlevel% equ 0 (
    echo ✅ 服务器正在运行，API 响应正常
    echo.
    echo 📊 服务器状态:
    curl -s http://localhost:3000/api/status
) else (
    echo ⚠️  服务器未运行或无响应
    echo.
    echo 💡 请运行以下命令启动服务器:
    echo    start-server.bat
    echo.
    echo 🔗 然后访问: http://localhost:3000
)

echo.
echo ========================================
echo           🎯 连通性检查汇总
echo ========================================
echo.
echo 📍 地址端口配置:
echo   - 服务器地址: localhost:3000
echo   - Web 客户端: http://localhost:3000
echo   - Socket.IO: ws://localhost:3000
echo.
echo 📱 三端配置状态:
echo   ✅ 服务器端: 端口 3000
echo   ✅ Web 端: ws://localhost:3000  
echo   ✅ Android 端: ws://localhost:3000
echo.
echo 🔄 连接流程:
echo   1. 启动服务器 (端口 3000)
echo   2. Web 端访问 http://localhost:3000
echo   3. Android 端连接 ws://localhost:3000
echo   4. 两端加入相同房间
echo   5. 建立 WebRTC P2P 连接
echo.
echo 🌐 局域网使用:
echo   将 localhost 替换为电脑的实际 IP 地址
echo   例如: ws://192.168.1.100:3000
echo.

goto :end

:error
echo.
echo ❌ 检查失败，请检查配置！
exit /b 1

:end
echo ✅ 所有配置检查完成！
pause 