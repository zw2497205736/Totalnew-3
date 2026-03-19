#!/bin/bash
# 测试 UNPROCESSED 是否真的生效

echo "测试音频源类型差异"
echo "================================"
echo ""

# 启动app
adb shell am start -n com.example.myapplication/.MainActivity
sleep 3

# 清空日志
adb logcat -c

# 点击开始检测
adb shell input tap 540 1250
sleep 5

echo "采集10秒数据，观察超声波能量..."
sleep 10

# 提取能量数据
echo ""
echo "================================"
echo "超声波信号强度分析："
echo "================================"

# 查找基线能量（校准完成时的能量）
BASELINE=$(adb logcat -d | grep "校准完成" | tail -1 | grep -o "基线幅度: [0-9.]*" | cut -d: -f2)
echo "基线能量: $BASELINE"

# 查找实际检测到的能量范围
echo ""
echo "实际能量范围："
adb logcat -d | grep "能量监控" | tail -20 | grep -o "原始=[0-9.]*" | sort -u

echo ""
echo "================================"
echo "判断标准："
echo "  如果是 UNPROCESSED:"
echo "    - 基线能量 > 10 (无AGC压缩)"
echo "    - 能量波动大 (无降噪)"
echo "  如果被预处理:"
echo "    - 基线能量 < 5 (AGC压缩了)"
echo "    - 能量很稳定 (降噪削平了)"
echo "================================"
