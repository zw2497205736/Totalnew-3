#!/bin/bash
# 停止日志录制并开始分析

echo "=== 停止日志录制 ==="

# 停止后台日志进程
if [ -f /tmp/logcat_pid.txt ]; then
    PID=$(cat /tmp/logcat_pid.txt)
    kill $PID 2>/dev/null
    echo "已停止日志录制进程 (PID: $PID)"
    rm /tmp/logcat_pid.txt
fi

# 查找最新的日志文件
LATEST_LOG=$(ls -t llap_failure_test_*.log 2>/dev/null | head -1)

if [ -z "$LATEST_LOG" ]; then
    echo "❌ 未找到日志文件"
    exit 1
fi

echo "✅ 日志文件: $LATEST_LOG"
echo "📊 文件大小: $(ls -lh "$LATEST_LOG" | awk '{print $5}')"
echo "📝 行数: $(wc -l < "$LATEST_LOG")"

# 显示最后几行
echo ""
echo "=== 日志预览（最后20行）==="
tail -20 "$LATEST_LOG"

echo ""
echo "=== 下一步: 运行分析脚本 ==="
echo "python3 analyze_llap_failure.py $LATEST_LOG"
