#!/bin/bash
# LLAP 失效场景日志导出脚本

echo "=== LLAP 失效场景测试日志导出 ==="

# 清除旧日志
adb logcat -c

echo "请执行以下测试场景："
echo "  场景1: 手部快速移动（1-2秒内移动10cm以上）"
echo "  场景2: 手部旋转移动（保持距离不变但旋转角度）"
echo "  场景3: 正常直线移动（作为对照组）"
echo ""
echo "按 Ctrl+C 停止录制..."

# 实时显示并保存关键日志
adb logcat -v time | grep -E "PhaseRangeFinder|FusedProximityDetector|相位|距离" | tee llap_failure_test_$(date +%Y%m%d_%H%M%S).log

echo ""
echo "日志已保存到当前目录"
