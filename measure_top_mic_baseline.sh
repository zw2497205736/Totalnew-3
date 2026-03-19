#!/bin/bash
# 测量上麦克风在不同音量下的基线能量

echo "========================================"
echo "  上麦克风基线能量测量脚本"
echo "========================================"
echo ""

# 解析参数
if [ $# -eq 2 ]; then
    MIN_VOL=$1
    MAX_VOL=$2
    echo "使用指定音量范围: $MIN_VOL 到 $MAX_VOL"
elif [ $# -eq 0 ]; then
    MIN_VOL=1
    MAX_VOL=15
    echo "未指定音量范围，使用默认: 1 到 15"
else
    echo "用法: $0 [最低音量] [最高音量]"
    echo "示例: $0 5 8  # 只测量音量5、6、7、8"
    echo "      $0      # 测量全部音量1-15"
    exit 1
fi

# 验证参数
if [ $MIN_VOL -lt 1 ] || [ $MIN_VOL -gt 15 ] || [ $MAX_VOL -lt 1 ] || [ $MAX_VOL -gt 15 ]; then
    echo "错误: 音量必须在1-15之间"
    exit 1
fi

if [ $MIN_VOL -gt $MAX_VOL ]; then
    echo "错误: 最低音量不能大于最高音量"
    exit 1
fi

echo ""
echo "准备工作："
echo "1. 手机平放在桌上，远离障碍物（>30cm）"
echo "2. 保持环境安静"
echo "3. 脚本会自动配置上麦克风"
echo ""
read -p "准备好了吗？按回车开始测量..." 

# 清空日志
adb logcat -c
echo "✓ 已清空日志"

# 获取最大音量
DEVICE_MAX_VOLUME=$(adb shell "dumpsys audio | grep 'STREAM_MUSIC' | grep -o 'Max: [0-9]*' | head -1 | cut -d: -f2 | tr -d ' '")
echo "✓ 检测到设备最大音量: $DEVICE_MAX_VOLUME"
echo ""

# 输出文件
OUTPUT_FILE="/tmp/top_mic_baseline_data.txt"
> $OUTPUT_FILE

echo "开始测量音量 $MIN_VOL 到 $MAX_VOL，每个音量采集30秒..."
echo ""

# 从指定音量范围逐个测量
for ((VOL=$MIN_VOL; VOL<=$MAX_VOL; VOL++)); do
    echo "----------------------------------------"
    echo "【音量 $VOL/$DEVICE_MAX_VOLUME】"
    echo "----------------------------------------"
    
    # 设置音量（使用AudioManager的input keyevent方式）
    # 先设为0
    for i in {1..15}; do
        adb shell input keyevent 25 > /dev/null 2>&1  # KEYCODE_VOLUME_DOWN
    done
    sleep 0.5
    # 再加到目标音量
    for ((i=1; i<=$VOL; i++)); do
        adb shell input keyevent 24 > /dev/null 2>&1  # KEYCODE_VOLUME_UP
    done
    sleep 0.5
    
    # 启动应用
    adb shell am force-stop com.example.myapplication > /dev/null 2>&1
    sleep 0.5
    adb shell am start -n com.example.myapplication/.MainActivity > /dev/null 2>&1
    sleep 2
    
    # 清空日志（在点击前清空）
    adb logcat -c
    
    # 先点击开始检测（应用开始发射超声波和校准）
    echo "  → 点击【开始检测】按钮..."
    adb shell input tap 540 1250 > /dev/null 2>&1
    sleep 1
    
    # 然后配置上麦克风（切换麦克风，但发射已经开始）
    echo "  → 配置上麦克风..."
    adb shell "tinymix 'UL1_CH2 ADDA_UL_CH2' '1' && \
               tinymix 'MISO0_MUX' 'UL1_CH2' && \
               tinymix 'ADC_R_Mux' 'Right Preamplifier' && \
               tinymix 'PGA_R_Mux' 'AIN2'" > /dev/null 2>&1
    sleep 1
    
    # 设置系统属性标识为上麦克风
    echo "  → 设置上麦克风标识..."
    adb shell setprop debug.ultrasonic.mic_type TOP > /dev/null 2>&1
    sleep 0.5
    
    # 等待校准完成
    echo "  → 等待校准完成（20次采样）..."
    sleep 4
    
    echo "  → 采集数据中（30秒）..."
    sleep 30
    
    # 提取基线数据
    BASELINE=$(adb logcat -d | grep "校准完成，基线幅度" | tail -1 | grep -o "基线幅度: [0-9.]*" | cut -d: -f2 | tr -d ' ')
    
    if [ -z "$BASELINE" ]; then
        echo "  ✗ 未获取到基线数据，重试..."
        # 重试一次
        adb logcat -c
        sleep 10
        BASELINE=$(adb logcat -d | grep "校准完成，基线幅度" | tail -1 | grep -o "基线幅度: [0-9.]*" | cut -d: -f2 | tr -d ' ')
    fi
    
    if [ ! -z "$BASELINE" ]; then
        echo "  ✓ 基线能量: $BASELINE"
        echo "$VOL:$BASELINE" >> $OUTPUT_FILE
    else
        echo "  ✗ 获取失败，跳过"
        echo "$VOL:ERROR" >> $OUTPUT_FILE
    fi
    
    sleep 1
done

echo ""
echo "========================================"
echo "  测量完成！"
echo "========================================"
echo ""
echo "结果已保存到: $OUTPUT_FILE"
echo ""
cat $OUTPUT_FILE
echo ""
echo "接下来我会自动生成Kotlin代码..."

# 生成Kotlin映射表代码
echo ""
echo "========================================"
echo "生成的Kotlin代码（复制到ProximityDetector.kt）："
echo "========================================"
echo ""
echo "// 上麦克风音量-基线映射表"
echo "private val TOP_MIC_VOLUME_BASELINE_MAP = mapOf("

while IFS=: read -r vol baseline; do
    if [ "$baseline" != "ERROR" ]; then
        echo "    $vol to ${baseline}f,"
    fi
done < $OUTPUT_FILE

echo ")"
echo ""

echo "保存到文件: /tmp/top_mic_baseline_kotlin.txt"
{
    echo "// 上麦克风音量-基线映射表"
    echo "private val TOP_MIC_VOLUME_BASELINE_MAP = mapOf("
    while IFS=: read -r vol baseline; do
        if [ "$baseline" != "ERROR" ]; then
            echo "    $vol to ${baseline}f,"
        fi
    done < $OUTPUT_FILE
    echo ")"
} > /tmp/top_mic_baseline_kotlin.txt

echo "✓ 完成！"
