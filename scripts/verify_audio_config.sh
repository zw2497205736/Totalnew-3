#!/bin/bash

# 验证音频硬件配置状态

export PATH=$PATH:$HOME/Library/Android/sdk/platform-tools

echo "========================================"
echo "音频硬件配置验证"
echo "========================================"
echo ""

# 检查 root 权限
echo "[1] Root 权限检查:"
ROOT_CHECK=$(adb shell "su 0 id" 2>&1)
if echo "$ROOT_CHECK" | grep -q "uid=0"; then
    echo "✓ Root 权限正常"
else
    echo "✗ Root 权限异常: $ROOT_CHECK"
    exit 1
fi
echo ""

# 检查扬声器配置
echo "[2] 扬声器（听筒）配置:"
echo "----------------------------------------"
adb shell "su 0 tinymix 'RCV Mux'" 2>&1 | grep ">"
adb shell "su 0 tinymix 'Tran_Pa_Scene'" 2>&1
adb shell "su 0 tinymix 'Ext_Speaker_Amp Switch'" 2>&1 | grep ">"
adb shell "su 0 tinymix 'DAC In Mux'" 2>&1 | grep ">"
echo ""

# 检查麦克风配置
echo "[3] 麦克风（顶部）配置:"
echo "----------------------------------------"
adb shell "su 0 tinymix 'PGA_R_Mux'" 2>&1 | grep ">"
adb shell "su 0 tinymix 'ADC_R_Mux'" 2>&1 | grep ">"
adb shell "su 0 tinymix 'DMIC0_MUX'" 2>&1 | grep ">"
adb shell "su 0 tinymix 'CM1_UL_MUX'" 2>&1 | grep ">"
echo ""

# 检查应用状态
echo "[4] 应用状态:"
echo "----------------------------------------"
APP_RUNNING=$(adb shell "ps | grep com.example.myapplication" 2>&1)
if [ -n "$APP_RUNNING" ]; then
    echo "✓ 应用正在运行"
    echo "$APP_RUNNING"
else
    echo "✗ 应用未运行"
fi
echo ""

# 显示最近的应用日志
echo "[5] 最近应用日志:"
echo "----------------------------------------"
adb logcat -d | grep "MainActivity" | tail -5
echo ""

echo "========================================"
echo "验证完成"
echo "========================================"
echo ""
echo "期望配置（上发上收模式）:"
echo "  扬声器: RCV Mux = Voice Playback"
echo "          Tran_Pa_Scene = 8"
echo "  麦克风:  PGA_R_Mux = AIN2"
echo "          ADC_R_Mux = Right Preamplifier"
echo "========================================"
