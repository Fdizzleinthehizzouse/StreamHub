@echo off
rem Starts StreamHub's key helper on the Fire TV, so the phone's remote pad
rem (arrows, OK, Back, Home) works. Needed once after each TV restart.
rem Usage: double-click, or: start-key-helper.bat 192.168.129.76

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" (
  echo Could not find adb at %ADB%
  echo Install Android Studio, or edit this file to point at adb.exe.
  goto end
)

set "TV=%~1"
if "%TV%"=="" set /p "TV=TV address (Settings - My Fire TV - About - Network), e.g. 192.168.129.76: "

"%ADB%" connect %TV%:5555
rem An older helper keeps running the old code after StreamHub is updated,
rem so any running one is stopped first and started fresh.
"%ADB%" -s %TV%:5555 shell "pkill -f com.felix.streamhub.keys.KeyServer; sleep 1"
"%ADB%" -s %TV%:5555 shell "CLASSPATH=$(pm path com.felix.streamhub | cut -d: -f2) nohup app_process /system/bin com.felix.streamhub.keys.KeyServer >/data/local/tmp/streamhub-keys.log 2>&1 &"
rem A 3-second pause that also works when not run from a console window.
ping -n 4 127.0.0.1 >nul
"%ADB%" -s %TV%:5555 shell cat /data/local/tmp/streamhub-keys.log

:end
if "%~1"=="" pause
