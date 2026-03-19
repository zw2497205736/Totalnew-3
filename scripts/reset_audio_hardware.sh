#!/system/bin/sh

# 重置音频硬件配置（需要 root 权限）
# 使用方法: adb push reset_audio_hardware.sh /data/local/tmp/
#          adb shell su -c "sh /data/local/tmp/reset_audio_hardware.sh"

echo "========================================="
echo "重置音频硬件配置"
echo "========================================="

# 重置 ADC
tinymix 'ADC_L_Mux' 'Idle'
tinymix 'ADC_R_Mux' 'Idle'
tinymix 'ADC_3_Mux' 'Idle'

# 重置 PGA
tinymix 'PGA_L_Mux' 'None'
tinymix 'PGA_R_Mux' 'None'
tinymix 'PGA_3_Mux' '0'

# 重置上行链路
tinymix 'UL_SRC_MUX' 'AMIC'
tinymix 'UL2_SRC_MUX' 'AMIC'
tinymix 'UL9_CH1 ADDA_UL_CH1' '0'
tinymix 'UL9_CH2 ADDA_UL_CH2' '0'
tinymix 'UL_CM1_CH1 ADDA_UL_CH1' '0'
tinymix 'UL_CM1_CH2 ADDA_UL_CH2' '0'
tinymix 'CM1_UL_MUX' 'CM1_2CH_PATH'

# 重置下行链路
tinymix 'ADDA_DL_CH1 DL0_CH1' '0'
tinymix 'ADDA_DL_CH2 DL0_CH1' '0'
tinymix 'Ext_Speaker_Amp Switch' '0'
tinymix 'Tran_PA_OPEN' '0'
tinymix 'RCV Mux' '0'
tinymix 'LOL Mux' '0'

echo '✓ 音频硬件已重置到默认状态'
echo '========================================='

