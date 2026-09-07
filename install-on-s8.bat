@echo off
setlocal

cd /d "%~dp0"

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"
set "ADB_LIST=%TEMP%\gear360-adb-devices.txt"

echo Checking connected Android devices...
"%ADB%" devices
echo.

set "TARGET_SERIAL=%~1"
if "%TARGET_SERIAL%"=="" (
    "%ADB%" devices -l > "%ADB_LIST%"
    for /f "tokens=1" %%D in ('findstr /C:"model:SM_G950F" "%ADB_LIST%"') do set "TARGET_SERIAL=%%D"
    del "%ADB_LIST%" >nul 2>&1
)

if "%TARGET_SERIAL%"=="" (
    echo No S8 SM-G950F found.
    echo Pass an adb serial manually: install-on-s8.bat SERIAL
    exit /b 1
)

echo Using S8 device: %TARGET_SERIAL%
echo.

echo Building and installing Gear360App debug APK...
call "%~dp0gradlew.bat" :app:assembleDebug
if errorlevel 1 (
    echo.
    echo Build failed.
    echo Check that USB debugging is enabled on the phone and that the RSA prompt was accepted.
    echo On e/OS, also allow file transfer/USB debugging from the notification if asked.
    exit /b 1
)

"%ADB%" -s "%TARGET_SERIAL%" install -r "%~dp0app\build\outputs\apk\debug\app-debug.apk"
if errorlevel 1 (
    echo.
    echo Install failed.
    exit /b 1
)

echo.
echo Done. Gear360App is installed.
