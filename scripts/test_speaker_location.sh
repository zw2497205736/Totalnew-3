#!/bin/bash

# 测试扬声器位置 - 播放可听见的测试音

export PATH=$PATH:$HOME/Library/Android/sdk/platform-tools

echo "========================================"
echo "扬声器位置测试"
echo "========================================"
echo ""

echo "📱 测试说明："
echo "   我们将通过播放 **可听见的测试音** 来确认扬声器位置"
echo "   1kHz 的音调应该清晰可闻"
echo ""

# 测试 1: 底部扬声器（默认）
echo "[测试 1/2] 底部扬声器（USAGE_MEDIA）"
echo "----------------------------------------"
echo "▶️  播放 3 秒测试音..."
echo "   请仔细听：声音应该从 **底部扬声器** 发出"
echo ""

adb shell am start -n com.example.myapplication/.MainActivity > /dev/null 2>&1
sleep 2

# 使用 adb shell input 模拟按钮点击会比较复杂
# 改为通过 intent 触发
adb shell am broadcast -a com.example.myapplication.TEST_SPEAKER --ei mode 0 > /dev/null 2>&1

echo "   ⏳ 播放中（3秒）..."
sleep 4
echo "   ✓ 播放完毕"
echo ""

# 测试 2: 顶部听筒（通话模式）
echo "[测试 2/2] 顶部听筒（USAGE_VOICE_COMMUNICATION）"
echo "----------------------------------------"
echo "▶️  播放 3 秒测试音..."
echo "   请仔细听：声音应该从 **顶部听筒** 发出"
echo "   （需要贴近耳朵才能听清楚）"
echo ""

adb shell am broadcast -a com.example.myapplication.TEST_SPEAKER --ei mode 1 > /dev/null 2>&1

echo "   ⏳ 播放中（3秒）..."
sleep 4
echo "   ✓ 播放完毕"
echo ""

echo "========================================"
echo "测试完成"
echo "========================================"
echo ""
echo "结论："
echo "  如果两次测试音来自不同位置 → 路由正常 ✅"
echo "  如果两次都从底部发出 → 需要配置 tinymix"
echo ""
echo "修复方法："
echo "  adb shell 'su 0 sh /data/local/tmp/setup_audio_hardware.sh'"
echo ""
