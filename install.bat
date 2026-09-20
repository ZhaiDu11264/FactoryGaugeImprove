@echo off
setlocal
cd /d "%~dp0"

net session >nul 2>&1
if not "%errorlevel%"=="0" (
    echo Need administrator rights - asking for elevation...
    powershell -NoProfile -Command "Start-Process -Verb RunAs -FilePath '%~f0' -ArgumentList '%*'"
    exit /b
)

echo ==================================================
echo   Factory Gauge Improve  -  install
echo   target: every instance whose mods folder has Create
echo ==================================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*

echo.
echo Log written to: %~dp0install.log
pause
