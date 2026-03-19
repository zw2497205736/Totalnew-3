#!/bin/bash

# 直接测试扬声器位置（不依赖应用）

export PATH=$PATH:$HOME/Library/Android/sdk/platform-tools

echo "========================================"
echo "🔊 扬声器位置测试 (使用 tinymix)"
echo "========================================"
echo ""

# 检查 root 权限
echo "[1] 检查 root 权限..."
ROOT_CHECK=$(adb shell "su 0 id" 2>&1)
if ! echo "$ROOT_CHECK" | grep -q "uid=0"; then
    echo "❌ 需要 root 权限"
    exit 1
fi
echo "✓ Root 权限正常"
echo ""

# 测试 1: 配置底部扬声器
echo "[测试 1/2] 底部扬声器"
echo "----------------------------------------"
echo "配置硬件路由到底部扬声器..."
adb shell "su 0 tinymix 'Ext_Speaker_Amp Switch' 1" > /dev/null 2>&1
adb shell "su 0 tinymix 'Tran_Pa_Scene' 0" > /dev/null 2>&1  # 场景 0 = 扬声器
adb shell "su 0 tinymix 'LOL Mux' 'Playback'" > /dev/null 2>&1

echo "▶️  播放测试音..."
echo "   📍 声音应该从 **底部扬声器** 发出（外放）"
echo ""

# 播放手机上的音频文件（如果有）
if adb shell "ls /data/audio48k.wav" > /dev/null 2>&1; then
    adb shell "su 0 tinyplay /data/audio48k.wav" 2>&1 | head -5
else
    echo "   ⚠️  未找到测试音频文件，跳过播放"
fi

sleep 2
echo ""

# 测试 2: 配置顶部听筒
echo "[测试 2/2] 顶部听筒"
echo "----------------------------------------"
echo "配置硬件路由到顶部听筒..."
adb shell "su 0 tinymix 'RCV Mux' 'Voice Playback'" > /dev/null 2>&1
adb shell "su 0 tinymix 'Tran_Pa_Scene' 8" > /dev/null 2>&1  # 场景 8 = 听筒
adb shell "su 0 tinymix 'Ext_Speaker_Amp Switch' 1" > /dev/null 2>&1

echo "▶️  播放测试音..."
echo "   📍 声音应该从 **顶部听筒** 发出（贴耳）"
echo ""

if adb shell "ls /data/audio48k.wav" > /dev/null 2>&1; then
    adb shell "su 0 tinyplay /data/audio48k.wav" 2>&1 | head -5
else
    echo "   ⚠️  未找到测试音频文件，跳过播放"
fi

echo ""
echo "========================================"
echo "测试完成"
echo "========================================"
echo ""
echo "💡 提示："
echo "   如果没有测试音频，可以推送一个："
echo "   adb push bat/audio48k.wav /data/"
echo ""
echo "   或使用应用内测试："
echo "   1. 运行应用并点击【开始检测】"
echo "   2. 如果听到声音在底部 → 配置 tinymix"
echo "   3. 运行: adb shell 'su 0 sh /data/local/tmp/setup_audio_hardware.sh'"
echo ""
