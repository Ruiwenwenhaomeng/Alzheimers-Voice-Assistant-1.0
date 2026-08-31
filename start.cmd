@echo off
setlocal
chcp 65001 >nul
title Alzheimer Voice Assistant Startup
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1"
if errorlevel 1 (
  echo.
  echo Startup failed. Please review the error messages above.
  pause
)
endlocal
