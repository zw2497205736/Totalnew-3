#!/usr/bin/env zsh
# 清理音频硬件配置，恢复默认状态
# 对应 tinyplay-top-spk.bat 中 pause 后的清理逻辑
set -euo pipefail

ADB="/Users/zjf/Library/Android/sdk/platform-tools/adb"

echo "========================================="
echo "  清理音频配置，恢复默认状态"
echo "========================================="

# 获取 root 权限
echo "→ 获取 root 权限..."
$ADB root >/dev/null 2>&1
sleep 1

# 检查设备连接
$ADB devices | grep -q device$ || { echo "✗ 设备未连接"; exit 1; }
echo "✓ 设备已连接 (root 模式)"
echo ""

echo "→ 清理扬声器配置..."

# 清理下行通道配置
$ADB shell "tinymix 'ADDA_DL_CH1 DL0_CH1' 0" >/dev/null 2>&1
$ADB shell "tinymix 'ADDA_DL_CH2 DL0_CH1' 0" >/dev/null 2>&1

# 关闭扬声器放大器
$ADB shell "tinymix 'Ext_Speaker_Amp Switch' 0" >/dev/null 2>&1

# 重置 PA 场景
$ADB shell "tinymix 'Tran_PA_OPEN' 0" >/dev/null 2>&1

# 重置 RCV 和 LOL Mux
$ADB shell "tinymix 'RCV Mux' 0" >/dev/null 2>&1
$ADB shell "tinymix 'LOL Mux' 0" >/dev/null 2>&1

echo "✓ 扬声器配置已清理"
echo ""

echo "→ 清理麦克风配置..."

# 重置麦克风通道
$ADB shell "tinymix 'UL1_CH2 ADDA_UL_CH2' 0" >/dev/null 2>&1
$ADB shell "tinymix 'MISO0_MUX' 'UL1_CH1'" >/dev/null 2>&1 || true
$ADB shell "tinymix 'ADC_R_Mux' 'Idle'" >/dev/null 2>&1 || true
$ADB shell "tinymix 'PGA_R_Mux' 'None'" >/dev/null 2>&1 || true

echo "✓ 麦克风配置已清理"
echo ""

echo "========================================="
echo "✓ 音频配置已恢复默认状态"
echo "========================================="
echo "提示: 您可以重新运行配置脚本来启用特定模式："
echo "  ./scripts/setup_top_speaker_and_mic.sh  (上发上收)"
echo "  ./scripts/force_top_mic_loop.sh         (仅顶部麦克风)"
echo "========================================="
