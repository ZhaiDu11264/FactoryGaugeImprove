@echo off
setlocal
cd /d "%~dp0"

net session >nul 2>&1
if not "%errorlevel%"=="0" (
    echo Need administrator rights - asking for elevation...
    rem An empty %* becomes -ArgumentList '' and Start-Process rejects it, so the
    rem elevated run has to be launched without that argument when there is none.
    if "%~1"=="" (
        powershell -NoProfile -Command "Start-Process -Verb RunAs -FilePath '%~f0'"
    ) else (
        powershell -NoProfile -Command "Start-Process -Verb RunAs -FilePath '%~f0' -ArgumentList '%*'"
    )
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
