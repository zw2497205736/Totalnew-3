#!/usr/bin/env zsh
# 配置顶部听筒（扬声器）+ 顶部麦克风（上发上收模式）
# 基于 tinyplay-top-spk.bat 的完整配置流程
set -euo pipefail

ADB="adb"

echo "========================================="
echo "  配置上发上收模式"
echo "  扬声器: 顶部听筒 (Receiver/Earpiece)"
echo "  麦克风: 顶部麦克风 (Top Mic)"
echo "========================================="

# 获取 root 权限
echo "→ 获取 root 权限..."
$ADB root >/dev/null 2>&1
sleep 1

# 检查设备连接
$ADB devices | grep -q device$ || { echo "✗ 设备未连接"; exit 1; }
echo "✓ 设备已连接 (root 模式)"
echo ""

# ========================================
# 第一部分：配置顶部听筒（扬声器）
# ========================================
echo "【1/2】配置顶部听筒（扬声器）..."
echo ""

# 步骤1: 配置 ADDA 下行通道（音频输出通道）
echo "→ 配置 ADDA_DL_CH1 和 ADDA_DL_CH2 (音频下行通道)..."
$ADB shell "tinymix 'ADDA_DL_CH1 DL0_CH1' 1" >/dev/null 2>&1
$ADB shell "tinymix 'ADDA_DL_CH2 DL0_CH1' 1" >/dev/null 2>&1

# 步骤2: 配置 DAC 输入多路复用器
echo "→ 配置 DAC In Mux (DAC 输入路径)..."
$ADB shell "tinymix 'DAC In Mux' 'Normal Path'" >/dev/null 2>&1

# 步骤3: 配置 RCV（Receiver/听筒）路由
echo "→ 配置 RCV Mux (听筒路由 → Voice Playback)..."
$ADB shell "tinymix 'RCV Mux' 'Voice Playback'" >/dev/null 2>&1

# 步骤4: 启用外部扬声器放大器开关
echo "→ 启用 Ext_Speaker_Amp Switch (扬声器放大器)..."
$ADB shell "tinymix 'Ext_Speaker_Amp Switch' 1" >/dev/null 2>&1

# 步骤5: 配置 PA（功率放大器）场景
echo "→ 配置 Tran_Pa_Scene (场景8: 听筒模式)..."
$ADB shell "tinymix 'Tran_Pa_Scene' 8" >/dev/null 2>&1

# 验证听筒配置
echo ""
echo "→ 验证听筒配置..."
RCV_MUX=$($ADB shell "tinymix | grep 'RCV Mux'" 2>/dev/null | awk '{print $NF}' || echo "N/A")
PA_SCENE=$($ADB shell "tinymix | grep 'Tran_Pa_Scene'" 2>/dev/null | awk '{print $NF}' || echo "N/A")
DAC_MUX=$($ADB shell "tinymix | grep 'DAC In Mux'" 2>/dev/null | awk '{print $NF}' || echo "N/A")
EXT_AMP=$($ADB shell "tinymix | grep 'Ext_Speaker_Amp Switch'" 2>/dev/null | awk '{print $NF}' || echo "N/A")

echo "✓ 听筒配置完成："
echo "  ADDA_DL_CH1/CH2: 已启用"
echo "  DAC In Mux: $DAC_MUX"
echo "  RCV Mux: $RCV_MUX"
echo "  Ext_Speaker_Amp: $EXT_AMP"
echo "  Tran_Pa_Scene: $PA_SCENE"
echo ""

# ========================================
# 第二部分：配置顶部麦克风
# ========================================
echo "【2/2】配置顶部麦克风..."
echo ""

# 配置 UL1_CH2 数据通道连接
echo "→ 配置 UL1_CH2 ADDA_UL_CH2 (数据通道)..."
$ADB shell "tinymix 'UL1_CH2 ADDA_UL_CH2' '1'" >/dev/null 2>&1

# 配置 MISO0 多路复用器
echo "→ 配置 MISO0_MUX (选择 UL1_CH2)..."
$ADB shell "tinymix 'MISO0_MUX' 'UL1_CH2'" >/dev/null 2>&1

# 配置 ADC 右声道路由
echo "→ 配置 ADC_R_Mux (右声道前置放大器)..."
$ADB shell "tinymix 'ADC_R_Mux' 'Right Preamplifier'" >/dev/null 2>&1

# 配置 PGA 右声道输入（关键：选择 AIN2 = 顶部麦克风）
echo "→ 配置 PGA_R_Mux (选择 AIN2 = 顶部麦克风)..."
$ADB shell "tinymix 'PGA_R_Mux' 'AIN2'" >/dev/null 2>&1

# 验证麦克风配置
MISO=$($ADB shell "tinymix | grep 'MISO0_MUX'" | awk '{print $NF}')
PGA=$($ADB shell "tinymix | grep 'PGA_R_Mux'" | awk '{print $NF}')
ADC=$($ADB shell "tinymix | grep 'ADC_R_Mux'" | awk '{print $NF}')

echo "✓ 麦克风配置完成："
echo "  MISO0_MUX: $MISO"
echo "  PGA_R_Mux: $PGA"
echo "  ADC_R_Mux: $ADC"
echo ""

# ========================================
# 设置麦克风类型标志（让app知道使用上麦克风）
# ========================================
echo "→ 设置麦克风类型标志..."
$ADB shell "setprop debug.ultrasonic.mic_type TOP" >/dev/null 2>&1
echo "✓ 已标记为上麦克风模式"
echo ""

# ========================================
# 总结
# ========================================
echo "========================================="
echo "✓✓✓ 上发上收模式配置完成 ✓✓✓"
echo "========================================="
echo "配置说明："
echo "  扬声器: 顶部听筒 (RCV → Voice Playback)"
echo "  麦克风: 顶部麦克风 (AIN2)"
echo "  声波路径: < 5cm (最短距离)"
echo "  用途: 近距离超声波检测"
echo "========================================="
echo ""
echo "验证配置："
echo "  adb shell \"tinymix | grep 'RCV Mux'\""
echo "  adb shell \"tinymix | grep 'PGA_R_Mux'\""
echo "========================================="
echo ""
echo "提示: 配置将持续生效，直到设备重启或手动清理"
echo "清理命令已添加到脚本末尾，如需清理请运行:"
echo "  ./scripts/cleanup_audio_config.sh"
echo "========================================="
