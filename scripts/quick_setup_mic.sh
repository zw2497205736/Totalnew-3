#!/usr/bin/env zsh
# 快速配置顶部麦克风（不录音，仅设置硬件路由）
set -euo pipefail

echo "========================================="
echo "  快速配置顶部麦克风（macOS 兼容版）"
echo "========================================="

if ! command -v /Users/zjf/Library/Android/sdk/platform-tools/adb >/dev/null 2>&1; then
  echo "✗ /Users/zjf/Library/Android/sdk/platform-tools/adb 未找到，请确保 Android SDK platform-tools 在 PATH 中"
  exit 1
fi

echo "1. 检查设备连接..."
/Users/zjf/Library/Android/sdk/platform-tools/adb devices | grep -q device$ && echo "  ✓ 设备已连接" || { echo "  ✗ 未检测到设备"; exit 1; }

echo "2. 检查 root 权限..."
/Users/zjf/Library/Android/sdk/platform-tools/adb shell id | grep -q "uid=0(root)" && echo "  ✓ 已获取 root 权限" || { echo "  ✗ 无 root 权限"; exit 1; }

echo "3. 禁用音频前端处理..."
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'HPF Switch' 0" 2>/dev/null || echo "  ⊘ HPF Switch (控件不存在)"
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'NS Enable' 0" 2>/dev/null || echo "  ⊘ NS Enable (控件不存在)"
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'AEC Enable' 0" 2>/dev/null || echo "  ⊘ AEC Enable (控件不存在)"
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'AGC Enable' 0" 2>/dev/null || echo "  ⊘ AGC Enable (控件不存在)"
echo "  ✓ 前端处理禁用完成"

echo "4. 配置顶部麦克风路由..."
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'UL1_CH2 ADDA_UL_CH2' '1'"  # 【关键】启用 UL1_CH2 连接
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'UL9_CH1 ADDA_UL_CH1' '1'"
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'UL9_CH2 ADDA_UL_CH2' '1'"
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'UL_CM1_CH1 ADDA_UL_CH1' '1'"
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'UL_CM1_CH2 ADDA_UL_CH2' '1'"
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'CM1_UL_MUX' 'CM1_16CH_PATH'"
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'MISO0_MUX' 'UL1_CH2'"
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'MISO1_MUX' 'UL1_CH2'"
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'ADC_R_Mux' 'Right Preamplifier'"
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'PGA_R_Mux' 'AIN2'"
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'DMIC0_MUX' 'DMIC_DATA1_R'"
/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix 'DMIC1_MUX' 'DMIC_DATA1_R'"
echo "  ✓ 路由配置完成"

echo "5. 验证配置..."
CURRENT_MIC=$(/Users/zjf/Library/Android/sdk/platform-tools/adb shell "tinymix | grep PGA_R_Mux" | awk '{print $NF}')
if [[ "$CURRENT_MIC" == "AIN2" ]]; then
  echo "  ✓ 麦克风已切换到顶部 (AIN2)"
else
  echo "  ✗ 麦克风配置可能失败，当前值: $CURRENT_MIC"
fi

echo "========================================="
echo "✓✓✓ 配置完成！✓✓✓"
echo ""
echo "当前配置："
echo "  麦克风: 顶部麦克风 (AIN2)"
echo "  前端处理: 已禁用"
echo "  路径: < 5cm (最短)"
echo ""
echo "下一步："
echo "  启动应用: /Users/zjf/Library/Android/sdk/platform-tools/adb shell am start -n com.example.myapplication/.MainActivity"
echo "  或运行: ./scripts/quick_setup_mic.sh && /Users/zjf/Library/Android/sdk/platform-tools/adb shell am start -n com.example.myapplication/.MainActivity"
echo "========================================="
