#!/bin/bash

# 测试不同麦克风的实际位置

export PATH=$PATH:$HOME/Library/Android/sdk/platform-tools

echo "========================================"
echo "🎤 麦克风位置测试"
echo "========================================"
echo ""

echo "测试方法："
echo "  用手指轻敲不同位置的麦克风"
echo "  观察录音文件的波形能量"
echo ""

# 测试 1: 顶部麦克风配置 + tinycap
echo "[测试 1/3] tinycap 顶部麦克风 (硬件级)"
echo "----------------------------------------"
echo "配置: PGA_R = AIN2, MISO = UL1_CH2"
echo ""
echo "操作: 轻敲手机顶部麦克风孔 3 次"
echo "按 Enter 开始录音（5秒）..."
read

adb shell "su 0 sh /data/local/tmp/setup_audio_hardware.sh" > /dev/null 2>&1
adb shell "su 0 tinycap /sdcard/test_top_mic.wav -D 0 -d 13 -c 1 -r 48000 -b 16 -p 1024 -n 8 -T 5" > /dev/null 2>&1
adb pull /sdcard/test_top_mic.wav /tmp/ > /dev/null 2>&1

echo "✓ 录音完成: /tmp/test_top_mic.wav"
echo ""

# 测试 2: 底部麦克风配置 + tinycap
echo "[测试 2/3] tinycap 底部麦克风 (硬件级)"
echo "----------------------------------------"
echo "配置: PGA_L = AIN0, MISO = UL1_CH1"
echo ""
echo "操作: 轻敲手机底部麦克风孔 3 次"
echo "按 Enter 开始录音（5秒）..."
read

# 配置底部麦克风
adb shell "su 0 tinymix 'PGA_L_Mux' 'AIN0'" > /dev/null 2>&1
adb shell "su 0 tinymix 'ADC_L_Mux' 'Left Preamplifier'" > /dev/null 2>&1
adb shell "su 0 tinymix 'MISO0_MUX' 'UL1_CH1'" > /dev/null 2>&1
adb shell "su 0 tinymix 'MISO1_MUX' 'UL1_CH1'" > /dev/null 2>&1

adb shell "su 0 tinycap /sdcard/test_bot_mic.wav -D 0 -d 13 -c 1 -r 48000 -b 16 -p 1024 -n 8 -T 5" > /dev/null 2>&1
adb pull /sdcard/test_bot_mic.wav /tmp/ > /dev/null 2>&1

echo "✓ 录音完成: /tmp/test_bot_mic.wav"
echo ""

# 恢复顶部麦克风配置
adb shell "su 0 sh /data/local/tmp/setup_audio_hardware.sh" > /dev/null 2>&1

echo "========================================"
echo "测试完成"
echo "========================================"
echo ""
echo "分析结果："
echo "  1. 打开两个 WAV 文件"
echo "  2. 观察波形能量"
echo "  3. 哪个文件在对应位置敲击时能量大"
echo "     → 那个就是实际使用的麦克风"
echo ""
echo "文件位置:"
echo "  /tmp/test_top_mic.wav  ← 顶部配置"
echo "  /tmp/test_bot_mic.wav  ← 底部配置"
echo ""
echo "在 macOS 上可以用 Audacity 或 QuickTime 打开查看"
echo ""
