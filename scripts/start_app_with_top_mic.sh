#!/usr/bin/env zsh
# 一键配置 + 启动应用（macOS 完全兼容版）
set -euo pipefail

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  超声波检测应用 - 一键启动（顶部麦克风模式）                ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# 1. 停止应用
echo "→ 停止旧实例..."
/Users/zjf/Library/Android/sdk/platform-tools/adb shell am force-stop com.example.myapplication 2>/dev/null || true
sleep 0.5

# 2. 配置硬件
echo "→ 配置顶部麦克风硬件路由..."
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'HPF Switch' 0" 2>/dev/null || true
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'NS Enable' 0" 2>/dev/null || true
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'AEC Enable' 0" 2>/dev/null || true
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'AGC Enable' 0" 2>/dev/null || true
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'UL9_CH1 ADDA_UL_CH1' '1'" >/dev/null
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'UL9_CH2 ADDA_UL_CH2' '1'" >/dev/null
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'UL_CM1_CH1 ADDA_UL_CH1' '1'" >/dev/null
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'UL_CM1_CH2 ADDA_UL_CH2' '1'" >/dev/null
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'CM1_UL_MUX' 'CM1_16CH_PATH'" >/dev/null
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'MISO0_MUX' 'UL1_CH2'" >/dev/null
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'MISO1_MUX' 'UL1_CH2'" >/dev/null
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'ADC_R_Mux' 'Right Preamplifier'" >/dev/null
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'PGA_R_Mux' 'AIN2'" >/dev/null
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'DMIC0_MUX' 'DMIC_DATA1_R'" >/dev/null
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'DMIC1_MUX' 'DMIC_DATA1_R'" >/dev/null
echo "  ✓ 硬件配置完成"

# 3. 验证
MIC=$(/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix | grep PGA_R_Mux" | awk '{print $NF}')
if [[ "$MIC" == "AIN2" ]]; then
  echo "  ✓ 麦克风: 顶部 (AIN2)"
else
  echo "  ⚠ 麦克风: $MIC (非预期)"
fi

# 4. 启动应用
echo "→ 启动应用..."
/Users/zjf/Library/Android/sdk/platform-tools/adb shell am start -n com.example.myapplication/.MainActivity >/dev/null
sleep 1

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  ✓ 应用已启动（顶部麦克风模式）                             ║"
echo "╠══════════════════════════════════════════════════════════════╣"
echo "║  配置:                                                        ║"
echo "║    麦克风: 顶部 (AIN2) - 听筒附近                            ║"
echo "║    扬声器: 顶部听筒 (RCV)                                    ║"
echo "║    距离: < 5cm (最短路径)                                    ║"
echo "║                                                              ║"
echo "║  下一步:                                                      ║"
echo "║    1. 在手机上点击【开始检测】                               ║"
echo "║    2. 手指靠近顶部麦克风测试                                 ║"
echo "║    3. 查看日志: /Users/zjf/Library/Android/sdk/platform-tools/adb logcat | grep UltrasonicRecorder        ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
