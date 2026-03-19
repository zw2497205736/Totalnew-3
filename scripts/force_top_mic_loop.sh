#!/usr/bin/env zsh
# 配置顶部麦克风（单次执行）
set -euo pipefail

ADB="/Users/zjf/Library/Android/sdk/platform-tools/adb"

echo "========================================="
echo "  配置顶部麦克风（单次执行）"
echo "========================================="

# 检查设备连接
$ADB devices | grep -q device$ || { echo "✗ 设备未连接"; exit 1; }
echo "✓ 设备已连接"

# 不需要 root 权限（tinymix 可以直接执行）
echo "✓ 开始配置..."
echo ""

# 核心配置命令
echo "→ 配置 UL1_CH2 ADDA_UL_CH2..."
$ADB shell "tinymix 'UL1_CH2 ADDA_UL_CH2' '1'" >/dev/null 2>&1

echo "→ 配置 MISO0_MUX..."
$ADB shell "tinymix 'MISO0_MUX' 'UL1_CH2'" >/dev/null 2>&1

echo "→ 配置 ADC_R_Mux..."
$ADB shell "tinymix 'ADC_R_Mux' 'Right Preamplifier'" >/dev/null 2>&1

echo "→ 配置 PGA_R_Mux..."
$ADB shell "tinymix 'PGA_R_Mux' 'AIN2'" >/dev/null 2>&1

# 验证配置
echo ""
echo "✓ 配置完成，验证结果："
MISO=$($ADB shell "tinymix | grep MISO0_MUX" | awk '{print $NF}')
PGA=$($ADB shell "tinymix | grep 'PGA_R_Mux'" | awk '{print $NF}')

echo "  MISO0_MUX: $MISO"
echo "  PGA_R_Mux: $PGA"
echo ""
echo "========================================="
echo "✓ 顶部麦克风配置完成"
echo "========================================="
