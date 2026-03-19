adb root

adb shell "tinymix 'UL9_CH1 ADDA_UL_CH1' '1'"

adb shell "tinymix 'UL9_CH2 ADDA_UL_CH2' '1'"

adb shell "tinymix 'UL_CM1_CH1 ADDA_UL_CH1' '1'"

adb shell "tinymix 'UL_CM1_CH2 ADDA_UL_CH2' '1'"

adb shell "tinymix 'CM1_UL_MUX' 'CM1_16CH_PATH'"

adb shell "tinymix 'MISO0_MUX' 'UL1_CH1'"

adb shell "tinymix 'MISO1_MUX' 'UL1_CH2'"

adb shell "tinymix 'ADC_L_Mux' 'Left Preamplifier'"

adb shell "tinymix 'PGA_L_Mux' 'AIN0'"

adb shell "tinymix 'ADC_R_Mux' 'Right Preamplifier'"

adb shell "tinymix 'PGA_R_Mux' 'AIN2'"

adb shell "tinymix 'DMIC0_MUX' 'DMIC_DATA0'"

adb shell "tinymix 'DMIC1_MUX' 'DMIC_DATA1_L_1'"

adb shell tinycap /sdcard/two-mic.wav -D 0 -d 13 -c 2 -r 48000 -b 16 -p 1024 -n 8 -T 10
adb pull /sdcard/two-mic.wav

adb shell "tinymix 'ADC_L_Mux' 'Idle'"
adb shell "tinymix 'ADC_R_Mux' 'Idle'"
adb shell "tinymix 'ADC_3_Mux' 'Idle'"
adb shell "tinymix 'PGA_L_Mux' 'None'"
adb shell "tinymix 'PGA_R_Mux' 'None'"
adb shell "tinymix 'PGA_3_Mux' 0"
adb shell "tinymix 'UL_SRC_MUX' 'AMIC'"
adb shell "tinymix 'UL2_SRC_MUX' 'AMIC'"
adb shell "tinymix 'UL9_CH1 ADDA_UL_CH1' '0'"
adb shell "tinymix 'UL9_CH2 ADDA_UL_CH2' '0'"
adb shell "tinymix 'UL_CM1_CH1 ADDA_UL_CH1' '0'"
adb shell "tinymix 'UL_CM1_CH2 ADDA_UL_CH2' '0'"
adb shell "tinymix 'CM1_UL_MUX' 'CM1_2CH_PATH'"
pause