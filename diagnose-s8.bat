@echo off
setlocal enabledelayedexpansion

set "PACKAGE=io.github.teccheck.gear360app"
set "APK=app\build\outputs\apk\debug\app-debug.apk"
set "SERIAL=%~1"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

if not "%SERIAL%"=="" goto :have_serial

for /f "skip=1 tokens=1,*" %%A in ('"%ADB%" devices -l') do (
    echo %%B | findstr /C:"model:SM-G950F" >nul
    if !errorlevel! equ 0 (
        set "SERIAL=%%A"
        goto :have_serial
    )
)

echo No S8 SM-G950F found.
echo Connect the S8 with USB debugging enabled, or pass the adb serial:
echo diagnose-s8.bat SERIAL
exit /b 1

:have_serial
echo Using device: %SERIAL%

echo Building APK...
call gradlew.bat :app:assembleDebug
if errorlevel 1 exit /b 1

if not exist "%APK%" (
    echo APK not found: %APK%
    exit /b 1
)

echo Clearing logcat...
"%ADB%" -s "%SERIAL%" logcat -c
if errorlevel 1 exit /b 1

echo Installing APK...
"%ADB%" -s "%SERIAL%" install -r "%APK%"
if errorlevel 1 exit /b 1

echo Force-stopping app...
"%ADB%" -s "%SERIAL%" shell am force-stop "%PACKAGE%"

echo Launching app...
"%ADB%" -s "%SERIAL%" shell am start -n "%PACKAGE%/.activity.StartActivity"

echo Waiting for startup logs...
ping -n 21 127.0.0.1 >nul

if not exist logs mkdir logs
set "OUT=logs\gear360-s8-logcat.txt"
set "FULL_OUT=logs\gear360-full-logcat.txt"
set "SAM_OUT=logs\samsung-accessory-package.txt"
set "BT_OUT=logs\bluetooth-manager.txt"

echo Capturing filtered logcat to %OUT%
"%ADB%" -s "%SERIAL%" logcat -d -v time AndroidRuntime:E LEGACY-SAM:D LEGACY-SAP:D LEGACY-RX:D LEGACY-TX:D G360-CONNECTION:D G360-ORCH:D G360-BT:D G360-PHYSICAL:D G360-SDP:D G360-RFCOMM:D G360-L2CAP:D G360-SAP-FRAME:D G360-CH204:D G360-SAP:D G360-RX:D G360-TX:D G360-PROTOCOL:D G360-CAPTURE:D Gear360Service:D BTMProviderService:D MessageLog:I SamsungAccessory:D SAAgent:D SAAgentV2:D SamAccessoryManager:D SASocket:D SimpleCameraActivity:D ScanActivity:D BluetoothAdapter:D BluetoothGatt:D *:S > "%OUT%"

echo Capturing full logcat to %FULL_OUT%
"%ADB%" -s "%SERIAL%" logcat -b all -d -v threadtime > "%FULL_OUT%"

echo Capturing Samsung Accessory package diagnostics to %SAM_OUT%
"%ADB%" -s "%SERIAL%" shell pm path com.samsung.accessory > "%SAM_OUT%"
"%ADB%" -s "%SERIAL%" shell dumpsys package com.samsung.accessory >> "%SAM_OUT%"

echo Capturing Bluetooth manager diagnostics to %BT_OUT%
"%ADB%" -s "%SERIAL%" shell dumpsys bluetooth_manager > "%BT_OUT%"

echo Done: %OUT%
echo Done: %SAM_OUT%
echo Done: %BT_OUT%
endlocal
