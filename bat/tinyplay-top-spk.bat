adb root
adb shell "tinymix  'ADDA_DL_CH1 DL0_CH1' 1"
adb shell "tinymix  'ADDA_DL_CH2 DL0_CH1' 1"


adb shell "tinymix  'DAC In Mux' 'Normal Path'"
adb shell "tinymix  'RCV Mux' 'Voice Playback'"
adb shell "tinymix  'Ext_Speaker_Amp Switch' 1"
adb shell "tinymix  'Tran_Pa_Scene' 8"


adb shell "tinyplay data/audio48k.wav"

pause

adb shell "tinymix  'ADDA_DL_CH1 DL0_CH1' 0"
adb shell "tinymix  'ADDA_DL_CH2 DL0_CH1' 0"
adb shell "tinymix  'Ext_Speaker_Amp Switch' 0"
adb shell "tinymix  'Tran_PA_OPEN' 0"
adb shell "tinymix  'RCV Mux' 0"
adb shell "tinymix  'LOL Mux' 0"
