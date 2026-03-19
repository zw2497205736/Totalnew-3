@echo off
set ADB=D:\SDK_Android\platform-tools\adb.exe
echo ========================================
echo Export WAV Recordings from Device
echo ========================================
echo.

REM 获取包名的外部文件目录
set PACKAGE_NAME=cn.sencs.llap

echo Listing recorded files...
%ADB% shell "ls -lh /sdcard/Android/data/%PACKAGE_NAME%/files/recordings/"

echo.
echo Pulling files to current directory...
if not exist recordings mkdir recordings
%ADB% pull /sdcard/Android/data/%PACKAGE_NAME%/files/recordings/ ./recordings/

echo.
echo Done! Files saved to: %cd%\recordings\
echo.
pause
