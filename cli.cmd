@echo off
setlocal
set "APP_JAVA=java"
where java >nul 2>nul
if errorlevel 1 if exist "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe" set "APP_JAVA=C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe"
"%APP_JAVA%" -cp "%~dp0app\*" com.society.updater.Main %*
endlocal
