@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion

REM =============================================================
REM  Setup: Top receiver (earpiece) + Top mic
REM  Converted from: scripts/setup_top_speaker_and_mic.sh
REM =============================================================

set "ADB=adb"
set "TINYMIX="

echo =========================================
echo   配置上发上收模式
echo   扬声器: 顶部听筒 (Receiver/Earpiece)
echo   麦克风: 顶部麦克风 (Top Mic)
echo =========================================
echo.

REM 获取 root 权限（忽略失败输出）
echo ^> 获取 root 权限...
%ADB% root >nul 2>nul
timeout /t 1 /nobreak >nul

REM 检查设备连接（必须是 device 状态）
set "DEVICE_OK="
for /f "skip=1 tokens=1,2" %%A in ('%ADB% devices 2^>nul') do (
    if /i "%%B"=="device" set "DEVICE_OK=1"
)
if not defined DEVICE_OK (
    echo x 设备未连接或未授权（adb devices 非 device 状态）
    echo.
    echo 请检查：
    echo   1^) 数据线/USB 调试
    echo   2^) 手机上是否弹出授权提示并点了允许
    echo   3^) adb 是否能识别到设备：%ADB% devices
    exit /b 1
)

echo OK 设备已连接 ^(root 模式已尝试开启^)
echo.

REM 探测设备端 tinymix 路径（你的设备 PATH 可能为空；优先检查 /data/local/tmp）
set "TINYMIX="

%ADB% shell "test -x /data/local/tmp/tinymix" >nul 2>nul
if not errorlevel 1 set "TINYMIX=/data/local/tmp/tinymix"

if not defined TINYMIX (
    %ADB% shell "test -x /vendor/bin/tinymix" >nul 2>nul
    if not errorlevel 1 set "TINYMIX=/vendor/bin/tinymix"
)

if not defined TINYMIX (
    %ADB% shell "test -x /system/bin/tinymix" >nul 2>nul
    if not errorlevel 1 set "TINYMIX=/system/bin/tinymix"
)

if not defined TINYMIX (
    %ADB% shell "test -x /system_ext/bin/tinymix" >nul 2>nul
    if not errorlevel 1 set "TINYMIX=/system_ext/bin/tinymix"
)

if not defined TINYMIX (
    %ADB% shell "test -x /product/bin/tinymix" >nul 2>nul
    if not errorlevel 1 set "TINYMIX=/product/bin/tinymix"
)

if not defined TINYMIX (
    echo [ERROR] 设备端未找到 tinymix：/system/bin/sh: tinymix: not found
    echo Hints:
    echo   1^) Try: adb shell ls -l /data/local/tmp/tinymix
    echo   2^) Try: adb shell ls -l /vendor/bin/tinymix
    echo   3^) If your ROM has no tinymix, you must provide it ^(tinyalsa^) or change routing method.
    exit /b 1
)

echo OK tinymix: !TINYMIX!
echo.

REM 可选：关闭常见前端处理（控件不存在会忽略）
%ADB% shell "!TINYMIX! set 'HPF Switch' 0" >nul 2>nul
%ADB% shell "!TINYMIX! set 'TX_IIR Enable' 0" >nul 2>nul
%ADB% shell "!TINYMIX! set 'NS Enable' 0" >nul 2>nul
%ADB% shell "!TINYMIX! set 'AEC Enable' 0" >nul 2>nul
%ADB% shell "!TINYMIX! set 'AGC Enable' 0" >nul 2>nul
%ADB% shell "!TINYMIX! set 'Speech Enhancement' 0" >nul 2>nul
%ADB% shell "!TINYMIX! set 'EC_REF_RX' 0" >nul 2>nul
%ADB% shell "!TINYMIX! set 'FLUENCE_ENABLE' 0" >nul 2>nul

REM ========================================
REM 第一部分：配置顶部听筒（扬声器）
REM ========================================
echo 【1/2】配置顶部听筒（扬声器）...
echo.

echo ^> 配置 ADDA_DL_CH1 和 ADDA_DL_CH2 ^(音频下行通道^)...
%ADB% shell "!TINYMIX! set 'ADDA_DL_CH1 DL0_CH1' 1"
if errorlevel 1 (
    echo [ERROR] tinymix 配置失败: ADDA_DL_CH1
    exit /b 1
)
%ADB% shell "!TINYMIX! set 'ADDA_DL_CH2 DL0_CH1' 1"
if errorlevel 1 (
    echo [ERROR] tinymix 配置失败: ADDA_DL_CH2
    exit /b 1
)

echo ^> 配置 DAC In Mux ^(DAC 输入路径^)...
%ADB% shell "!TINYMIX! set 'DAC In Mux' 'Normal Path'"
if errorlevel 1 (
    echo [ERROR] tinymix 配置失败: DAC In Mux
    exit /b 1
)

echo ^> 配置 RCV Mux ^(听筒路由 ^> Voice Playback^)...
%ADB% shell "!TINYMIX! set 'RCV Mux' 'Voice Playback'"
if errorlevel 1 (
    echo [ERROR] tinymix 配置失败: RCV Mux
    exit /b 1
)

echo ^> 启用 Ext_Speaker_Amp Switch ^(扬声器放大器^)...
%ADB% shell "!TINYMIX! set 'Ext_Speaker_Amp Switch' 1"
if errorlevel 1 (
    echo [ERROR] tinymix 配置失败: Ext_Speaker_Amp Switch
    exit /b 1
)

REM 尽量避免走底部/外放路径：关闭 LOL，打开 PA（控件不存在则忽略）
%ADB% shell "!TINYMIX! set 'LOL Mux' 0" >nul 2>nul
%ADB% shell "!TINYMIX! set 'Tran_PA_OPEN' 1" >nul 2>nul

echo ^> 配置 Tran_Pa_Scene ^(场景8: 听筒模式^)...
%ADB% shell "!TINYMIX! set 'Tran_Pa_Scene' 8"
if errorlevel 1 (
    echo [ERROR] tinymix 配置失败: Tran_Pa_Scene
    exit /b 1
)

echo.
echo ^> 验证听筒配置...

echo OK Speaker routing applied.
echo   DAC In Mux:
%ADB% shell "!TINYMIX! get 'DAC In Mux'" 2>nul
echo   RCV Mux:
%ADB% shell "!TINYMIX! get 'RCV Mux'" 2>nul
echo   LOL Mux:
%ADB% shell "!TINYMIX! get 'LOL Mux'" 2>nul
echo   Ext_Speaker_Amp:
%ADB% shell "!TINYMIX! get 'Ext_Speaker_Amp Switch'" 2>nul
echo   Tran_PA_OPEN:
%ADB% shell "!TINYMIX! get 'Tran_PA_OPEN'" 2>nul
echo   Tran_Pa_Scene:
%ADB% shell "!TINYMIX! get 'Tran_Pa_Scene'" 2>nul
echo.

REM ========================================
REM 第二部分：配置顶部麦克风
REM ========================================
echo 【2/2】配置顶部麦克风...
echo.

REM Extra routing (UL9/CM1/DMIC) to better force top-mic path
echo Applying UL9/CM1 routing...
%ADB% shell "!TINYMIX! set 'UL9_CH1 ADDA_UL_CH1' 1" >nul 2>nul
%ADB% shell "!TINYMIX! set 'UL9_CH2 ADDA_UL_CH2' 1" >nul 2>nul
%ADB% shell "!TINYMIX! set 'UL_CM1_CH1 ADDA_UL_CH1' 1" >nul 2>nul
%ADB% shell "!TINYMIX! set 'UL_CM1_CH2 ADDA_UL_CH2' 1" >nul 2>nul
%ADB% shell "!TINYMIX! set 'CM1_UL_MUX' 'CM1_16CH_PATH'" >nul 2>nul

REM 可选：也打开 UL1_CH1（某些机型上收声可能走 CH1）
%ADB% shell "!TINYMIX! set 'UL1_CH1 ADDA_UL_CH1' 1" >nul 2>nul

echo ^> 配置 UL1_CH2 ADDA_UL_CH2 ^(数据通道^)...
%ADB% shell "!TINYMIX! set 'UL1_CH2 ADDA_UL_CH2' '1'"
if errorlevel 1 (
    echo [ERROR] tinymix 配置失败: UL1_CH2 ADDA_UL_CH2
    exit /b 1
)

echo ^> 配置 MISO0_MUX ^(选择 UL1_CH2^)...
%ADB% shell "!TINYMIX! set 'MISO0_MUX' 'UL1_CH2'"
if errorlevel 1 (
    echo [ERROR] tinymix 配置失败: MISO0_MUX
    exit /b 1
)

echo ^> Optional: 配置 MISO1_MUX ^(也选择 UL1_CH2^)...
%ADB% shell "!TINYMIX! set 'MISO1_MUX' 'UL1_CH2'" >nul 2>nul

echo ^> 配置 ADC_R_Mux ^(右声道前置放大器^)...
%ADB% shell "!TINYMIX! set 'ADC_R_Mux' 'Right Preamplifier'"
if errorlevel 1 (
    echo [ERROR] tinymix 配置失败: ADC_R_Mux
    exit /b 1
)

echo ^> Optional: 关闭/清空左声道路由，避免误用底麦...
%ADB% shell "!TINYMIX! set 'ADC_L_Mux' 'Idle'" >nul 2>nul
%ADB% shell "!TINYMIX! set 'PGA_L_Mux' 'None'" >nul 2>nul

echo ^> 配置 PGA_R_Mux ^(选择 AIN2 = 顶部麦克风^)...
%ADB% shell "!TINYMIX! set 'PGA_R_Mux' 'AIN2'"
if errorlevel 1 (
    echo [ERROR] tinymix 配置失败: PGA_R_Mux
    exit /b 1
)

echo ^> Optional: 配置 DMIC 路由（若顶麦为数字麦，常用 DATA1_R）...
%ADB% shell "!TINYMIX! set 'DMIC0_MUX' 'DMIC_DATA1_R'" >nul 2>nul
%ADB% shell "!TINYMIX! set 'DMIC1_MUX' 'DMIC_DATA1_R'" >nul 2>nul

echo OK 麦克风配置完成：
echo   MISO0_MUX:
%ADB% shell "!TINYMIX! get 'MISO0_MUX'" 2>nul
echo   PGA_R_Mux:
%ADB% shell "!TINYMIX! get 'PGA_R_Mux'" 2>nul
echo   ADC_R_Mux:
%ADB% shell "!TINYMIX! get 'ADC_R_Mux'" 2>nul
echo.

REM Set mic type flag for app
echo ^> Set property: debug.ultrasonic.mic_type=TOP
%ADB% shell "setprop debug.ultrasonic.mic_type TOP"
if errorlevel 1 (
    echo [ERROR] setprop 失败: debug.ultrasonic.mic_type TOP
    exit /b 1
)
echo OK 已标记为上麦克风模式
echo.

echo =========================================
echo OK 上发上收模式配置完成
echo =========================================
echo Summary:
echo   Speaker: Receiver ^(RCV ^> Voice Playback^)
echo   Mic: Top mic ^(AIN2^)
echo   Purpose: Near-field ultrasonic detection
echo =========================================
echo.
echo 验证配置（可手动执行）：
echo   adb shell "tinymix get 'RCV Mux'"
echo   adb shell "tinymix get 'PGA_R_Mux'"
echo =========================================
echo.
echo Note: Settings persist until reboot or cleanup.
echo Cleanup (if available):
echo   scripts\cleanup_audio_config.sh
echo =========================================
echo.

REM If the app/HAL overwrites routes after startup, run with: setup_top_speaker_and_mic.bat --loop
if /i "%~1"=="--loop" (
    echo Loop mode enabled: reapplying routes every 2 seconds. Press Ctrl+C to stop.
    :LOOP_REAPPLY
    %ADB% shell "!TINYMIX! set 'ADDA_DL_CH1 DL0_CH1' 1" >nul 2>nul
    %ADB% shell "!TINYMIX! set 'ADDA_DL_CH2 DL0_CH1' 1" >nul 2>nul
    %ADB% shell "!TINYMIX! set 'DAC In Mux' 'Normal Path'" >nul 2>nul
    %ADB% shell "!TINYMIX! set 'RCV Mux' 'Voice Playback'" >nul 2>nul
    %ADB% shell "!TINYMIX! set 'Ext_Speaker_Amp Switch' 1" >nul 2>nul
    %ADB% shell "!TINYMIX! set 'Tran_PA_OPEN' 1" >nul 2>nul
    %ADB% shell "!TINYMIX! set 'Tran_Pa_Scene' 8" >nul 2>nul

    %ADB% shell "!TINYMIX! set 'UL9_CH1 ADDA_UL_CH1' 1" >nul 2>nul
    %ADB% shell "!TINYMIX! set 'UL9_CH2 ADDA_UL_CH2' 1" >nul 2>nul
    %ADB% shell "!TINYMIX! set 'UL_CM1_CH1 ADDA_UL_CH1' 1" >nul 2>nul
    %ADB% shell "!TINYMIX! set 'UL_CM1_CH2 ADDA_UL_CH2' 1" >nul 2>nul
    %ADB% shell "!TINYMIX! set 'CM1_UL_MUX' 'CM1_16CH_PATH'" >nul 2>nul

    %ADB% shell "!TINYMIX! set 'UL1_CH2 ADDA_UL_CH2' 1" >nul 2>nul
    %ADB% shell "!TINYMIX! set 'MISO0_MUX' 'UL1_CH2'" >nul 2>nul
    %ADB% shell "!TINYMIX! set 'MISO1_MUX' 'UL1_CH2'" >nul 2>nul
    %ADB% shell "!TINYMIX! set 'ADC_R_Mux' 'Right Preamplifier'" >nul 2>nul
    %ADB% shell "!TINYMIX! set 'PGA_R_Mux' 'AIN2'" >nul 2>nul
    %ADB% shell "!TINYMIX! set 'DMIC0_MUX' 'DMIC_DATA1_R'" >nul 2>nul
    %ADB% shell "!TINYMIX! set 'DMIC1_MUX' 'DMIC_DATA1_R'" >nul 2>nul

    timeout /t 2 /nobreak >nul
    goto LOOP_REAPPLY
)

endlocal
exit /b 0
