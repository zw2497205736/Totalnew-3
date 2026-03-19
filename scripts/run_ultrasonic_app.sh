#!/bin/bash

# 一键启动超声波检测应用（配置硬件 + 安装 + 启动）

export PATH=$PATH:$HOME/Library/Android/sdk/platform-tools

echo "========================================"
echo "超声波检测应用 - 一键启动脚本"
echo "========================================"

# 1. 配置底层音频硬件
echo ""
echo "[1/4] 配置底层音频硬件（需要 root 权限）..."
adb push /Users/zjf/LLAP/Totalnew/scripts/setup_audio_hardware.sh /data/local/tmp/ > /dev/null 2>&1
adb shell "su 0 sh /data/local/tmp/setup_audio_hardware.sh"

if [ $? -ne 0 ]; then
    echo "⚠️  硬件配置失败，将使用 Android API 进行回退配置"
fi

# 2. 编译应用
echo ""
echo "[2/4] 编译应用..."
cd /Users/zjf/LLAP/Totalnew
./gradlew assembleDebug --quiet

if [ $? -ne 0 ]; then
    echo "❌ 编译失败"
    exit 1
fi
echo "✓ 编译成功"

# 3. 安装应用
echo ""
echo "[3/4] 安装应用..."
adb install -r app/build/outputs/apk/debug/app-debug.apk 2>&1 | grep -E "Success|Failed"

if [ $? -ne 0 ]; then
    echo "❌ 安装失败"
    exit 1
fi
echo "✓ 安装成功"

# 4. 启动应用
echo ""
echo "[4/4] 启动应用..."
adb shell am force-stop com.example.myapplication
sleep 1
adb logcat -c
adb shell am start -n com.example.myapplication/.MainActivity > /dev/null 2>&1
echo "✓ 应用已启动"

# 等待应用完全启动
sleep 2

# 5. 重新确认硬件配置（防止被应用重置）
echo ""
echo "[5/5] 重新确认硬件配置..."
adb shell "su 0 sh /data/local/tmp/setup_audio_hardware.sh" > /dev/null 2>&1
echo "✓ 硬件配置已锁定"

# 5. 显示日志
echo ""
echo "========================================="
echo "✓ 完成！等待 2 秒后显示日志..."
echo "========================================="
sleep 2

echo ""
echo ">>> 硬件配置验证:"
echo "扬声器: $(adb shell 'su 0 tinymix \"RCV Mux\"' 2>&1 | grep '>')"
echo "麦克风:  $(adb shell 'su 0 tinymix \"PGA_R_Mux\"' 2>&1 | grep '>')"
echo ""

echo ">>> 音频配置日志:"
adb logcat -d | grep -E "MainActivity|AudioHardware" | grep -E "音频配置|硬件|采样率" | tail -10

echo ""
echo "========================================="
echo "提示:"
echo "1. 点击【开始检测】按钮开始"
echo "2. 点击【校准】按钮进行基线校准"
echo "3. 可以点击【保存 WAV】录制原始信号"
echo "4. 使用 'adb logcat | grep MainActivity' 查看实时日志"
echo "========================================="
