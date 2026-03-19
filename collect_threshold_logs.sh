#!/bin/bash
# 收集动态阈值测试日志

echo "========================================"
echo "  动态阈值测试日志收集脚本"
echo "========================================"
echo ""
echo "测试说明："
echo "1. 在不同音量下（1-15）测试"
echo "2. 尝试咳嗽、说话等噪声干扰"
echo "3. 尝试手靠近/远离"
echo "4. 脚本会记录所有能量监控和状态切换日志"
echo ""
read -p "准备好了吗？按回车开始收集日志..." 

# 清空旧日志
adb logcat -c

# 输出文件
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
OUTPUT_FILE="threshold_test_${TIMESTAMP}.log"

echo "✓ 开始收集日志，输出到: $OUTPUT_FILE"
echo "  按 Ctrl+C 停止收集"
echo ""

# 实时显示并保存日志
adb logcat -v time | tee $OUTPUT_FILE | grep -E "ProximityDetector|音量监控|状态切换"

echo ""
echo "✓ 日志已保存到: $OUTPUT_FILE"
echo ""
echo "分析命令："
echo "  查看所有状态切换:"
echo "    grep '状态切换' $OUTPUT_FILE"
echo ""
echo "  查看不同音量的能量比:"
echo "    grep '音量监控' $OUTPUT_FILE | grep '音量=1/'"
echo "    grep '音量监控' $OUTPUT_FILE | grep '音量=5/'"
echo "    grep '音量监控' $OUTPUT_FILE | grep '音量=10/'"
