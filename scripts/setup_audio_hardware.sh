#!/system/bin/sh

# 配置顶部麦克风和扬声器的底层硬件（需要 root 权限）
# 使用方法: adb push setup_audio_hardware.sh /data/local/tmp/
#          adb shell su -c "sh /data/local/tmp/setup_audio_hardware.sh"

echo "========================================="
echo "配置超声波音频硬件"
echo "模式: 上发上收（顶部扬声器 + 顶部麦克风）"
echo "========================================="

# 1. 配置顶部扬声器（听筒）
echo '>>> 配置顶部扬声器（听筒）'
tinymix 'ADDA_DL_CH1 DL0_CH1' '1'
tinymix 'ADDA_DL_CH2 DL0_CH1' '1'
tinymix 'DAC In Mux' 'Normal Path'
tinymix 'RCV Mux' 'Voice Playback'
tinymix 'Ext_Speaker_Amp Switch' '1'
tinymix 'Tran_Pa_Scene' '8'
echo '✓ 顶部扬声器配置完成'

# 2. 配置顶部麦克风
echo '>>> 配置顶部麦克风'
tinymix 'UL9_CH1 ADDA_UL_CH1' '1'
tinymix 'UL9_CH2 ADDA_UL_CH2' '1'
tinymix 'UL_CM1_CH1 ADDA_UL_CH1' '1'
tinymix 'UL_CM1_CH2 ADDA_UL_CH2' '1'
tinymix 'CM1_UL_MUX' 'CM1_16CH_PATH'
tinymix 'MISO0_MUX' 'UL1_CH2'
tinymix 'MISO1_MUX' 'UL1_CH2'
tinymix 'ADC_R_Mux' 'Right Preamplifier'
tinymix 'PGA_R_Mux' 'AIN2'
tinymix 'DMIC0_MUX' 'DMIC_DATA1_R'
tinymix 'DMIC1_MUX' 'DMIC_DATA1_R'
echo '✓ 顶部麦克风配置完成'

# 3. 验证配置
echo '>>> 验证配置'
echo '扬声器配置:'
tinymix 'RCV Mux'
tinymix 'Tran_Pa_Scene'
echo '麦克风配置:'
tinymix 'PGA_R_Mux'
tinymix 'ADC_R_Mux'

echo '========================================='
echo '✓ 音频硬件配置完成！'
echo '现在可以运行 Android 应用进行超声波检测'
echo '========================================='

