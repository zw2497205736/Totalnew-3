adb root
adb shell "tinymix 'ADDA_DL_CH1 DL2_CH1' 1"
adb shell "tinymix 'ADDA_DL_CH2 DL2_CH2' 1"
adb shell "tinymix 'DAC In Mux' 'Normal Path'"
adb shell "tinymix 'RCV Mux' 'Voice Playback'"
adb shell "tinymix 'Ext_Speaker_Amp Switch' 1"
adb shell "tinymix 'Handset Volume' 0"
adb shell "tinymix 'FSM_Scene' 15"

adb shell "tinyplay /sdcard/hotel.wav -D 0 -d 2"

adb shell "tinymix 'RCV Mux' 'Open'"
adb shell "tinymix 'ADDA_DL_CH1 DL2_CH1' 0"
adb shell "tinymix 'ADDA_DL_CH2 DL2_CH2' 0"
adb shell "tinymix 'Ext_Speaker_Amp Switch' 0"

start .
pause

