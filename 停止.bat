@echo off
chcp 65001 >nul
title Stop QQ Voice Helper

set "FOUND="
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8791" ^| findstr "LISTENING"') do (
  set "FOUND=1"
  taskkill /PID %%a /F >nul 2>&1
)

if defined FOUND (
  echo   Stopped. Port 8791 released.
) else (
  echo   Not running.
)
timeout /t 2 >nul
