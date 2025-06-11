@echo off
chcp 65001 >nul
echo ========================================
echo     WebRTC 网络连接测试工具
echo ========================================
echo.

set "SERVER_IP=192.168.31.121"
set "SERVER_PORT=3000"

echo [1/5] 检查本地网络配置...
echo 📍 当前电脑IP地址:
ipconfig | findstr "IPv4" | findstr "192.168"
echo.

echo [2/5] 检查服务器进程...
tasklist | findstr "node.exe" >nul
if %errorlevel% equ 0 (
    echo ✅ Node.js 服务器进程正在运行
) else (
    echo ❌ Node.js 服务器进程未运行
    echo 💡 请先运行 start-server.bat
)
echo.

echo [3/5] 测试端口连通性...
echo 🔍 测试端口 %SERVER_PORT% 是否开放...
netstat -an | findstr ":%SERVER_PORT%" >nul
if %errorlevel% equ 0 (
    echo ✅ 端口 %SERVER_PORT% 正在监听
    netstat -an | findstr ":%SERVER_PORT%"
) else (
    echo ❌ 端口 %SERVER_PORT% 未监听
    echo 💡 请确保服务器已启动
)
echo.

echo [4/5] 测试 HTTP 访问...
echo 🌐 测试 localhost 访问...
curl -s -I http://localhost:%SERVER_PORT% >nul 2>&1
if %errorlevel% equ 0 (
    echo ✅ localhost 访问正常
) else (
    echo ❌ localhost 访问失败
)

echo 🌐 测试内网IP访问...
curl -s -I http://%SERVER_IP%:%SERVER_PORT% >nul 2>&1
if %errorlevel% equ 0 (
    echo ✅ 内网IP访问正常
) else (
    echo ❌ 内网IP访问失败
    echo 💡 请检查防火墙设置
)
echo.

echo [5/5] 防火墙检查建议...
echo 🛡️  防火墙配置检查:
netsh advfirewall firewall show rule name="Node.js" >nul 2>&1
if %errorlevel% equ 0 (
    echo ✅ 发现 Node.js 防火墙规则
) else (
    echo ⚠️  未发现 Node.js 防火墙规则
    echo 💡 建议添加防火墙例外：
    echo    netsh advfirewall firewall add rule name="Node.js WebRTC" dir=in action=allow protocol=TCP localport=%SERVER_PORT%
)
echo.

echo ========================================
echo           📱 移动设备连接指南
echo ========================================
echo.
echo 🔗 Android 设备连接配置:
echo   默认服务器地址: ws://%SERVER_IP%:%SERVER_PORT%
echo   ✅ 已自动配置到 Android 应用中
echo.
echo 🌐 Web 客户端访问地址:
echo   💻 本机访问: http://localhost:%SERVER_PORT%
echo   📱 手机访问: http://%SERVER_IP%:%SERVER_PORT%
echo.
echo 📋 连接测试步骤:
echo   1. 确保手机和电脑连接同一WiFi
echo   2. 启动服务器 (start-server.bat)
echo   3. 手机浏览器访问: http://%SERVER_IP%:%SERVER_PORT%
echo   4. 如果网页能打开，说明网络连接正常
echo   5. 运行 Android 应用进行投屏测试
echo.
echo ⚠️  常见问题解决:
echo   - 连接失败: 检查防火墙和网络
echo   - 地址错误: 重新运行 setup-network.bat
echo   - 权限问题: 以管理员身份运行脚本
echo.

pause 