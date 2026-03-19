@echo off
set ADB=D:\SDK_Android\platform-tools\adb.exe
echo ========================================
echo WAV Recording Debug Logs
echo ========================================
echo.
echo Clearing logcat...
%ADB% logcat -c

echo.
echo Monitoring logs (Press Ctrl+C to stop)...
echo.
echo Filter: WavWriter, AudioController, MainActivity, AndroidRuntime (crashes)
echo.
%ADB% logcat WavWriter:V AudioController:V MainActivity:V AndroidRuntime:E *:S
