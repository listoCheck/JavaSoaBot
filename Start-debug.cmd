@echo off
cd /d "%~dp0"
java "-Dapp.dir=%~dp0." -jar "%~dp0target\clipboard-bot.jar"
pause
