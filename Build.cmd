@echo off
setlocal
cd /d "%~dp0"
if not exist "%JAVA_HOME%\bin\javac.exe" (
  for /d %%J in ("%ProgramFiles%\Java\jdk-*" "%ProgramFiles%\Eclipse Adoptium\jdk-*") do if exist "%%~J\bin\javac.exe" set "JAVA_HOME=%%~J"
)
call mvn -B package
if errorlevel 1 (
  echo Build failed.
  pause
  exit /b 1
)
echo Build complete. Run Start.cmd.
pause
