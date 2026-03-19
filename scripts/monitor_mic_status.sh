#!/usr/bin/env zsh
# 实时监控麦克风配置状态

echo "实时监控麦克风配置状态（每2秒刷新）"
echo "按 Ctrl+C 停止"
echo ""

while true; do
  clear
  echo "════════════════════════════════════════════════════════════════"
  echo "  📊 麦克风硬件配置实时状态"
  echo "════════════════════════════════════════════════════════════════"
  echo ""
  
  MIC=$(adb shell "tinymix | grep PGA_R_Mux" 2>/dev/null | awk '{print $NF}')
  ADC=$(adb shell "tinymix | grep ADC_R_Mux" 2>/dev/null | awk '{print $NF}')
  
  if [[ "$MIC" == "AIN2" ]]; then
    echo "✅ PGA_R_Mux: AIN2 (顶部麦克风) ✓"
  else
    echo "❌ PGA_R_Mux: $MIC (底部/默认)"
  fi
  
  if [[ "$ADC" == "Preamplifier" ]] || [[ "$ADC" =~ "Preamplifier" ]]; then
    echo "✅ ADC_R_Mux: Right Preamplifier ✓"
  else
    echo "❌ ADC_R_Mux: $ADC"
  fi
  
  echo ""
  echo "════════════════════════════════════════════════════════════════"
  echo "$(date '+%Y-%m-%d %H:%M:%S')"
  echo ""
  
  sleep 2
done
