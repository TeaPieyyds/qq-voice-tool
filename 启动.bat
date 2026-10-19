@echo off
chcp 65001 >nul
title QQ Voice Helper
cd /d "%~dp0"

where node >nul 2>&1
if errorlevel 1 (
  echo.
  echo   Node.js not found. Please install Node.js 18+ first: https://nodejs.org/
  echo.
  pause
  exit /b 1
)

if not exist "node_modules" (
  echo   First run: installing dependencies...
  call npm install --omit=dev
  if errorlevel 1 (
    echo   npm install failed. Check your network, or run it manually in this folder.
    pause
    exit /b 1
  )
)

node server.js
if errorlevel 1 (
  echo.
  echo   Server exited with an error. See messages above.
  pause
)
