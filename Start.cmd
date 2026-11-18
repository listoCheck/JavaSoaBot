@echo off
cd /d "%~dp0"
if not exist "config.properties" copy /y "config.example.properties" "config.properties" >nul
if not exist "target\clipboard-bot.jar" (
  echo Run Build.cmd first.
  pause
  exit /b 1
)
start "Clipboard Bot" javaw "-Dapp.dir=%~dp0." -jar "%~dp0target\clipboard-bot.jar"
