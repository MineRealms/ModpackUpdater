@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
set "GRADLE=H:\MinecraftMods\gradle-8.14.5\bin\gradle.bat"
call "%GRADLE%" -p "%~dp0." build installDist --console=plain
if errorlevel 1 exit /b 1
if exist "%~dp0dist\ModpackUpdater" rmdir /s /q "%~dp0dist\ModpackUpdater"
call "%JAVA_HOME%\bin\jpackage.exe" --type app-image --name ModpackUpdater --input "%~dp0build\install\ModpackUpdater\lib" --main-jar ModpackUpdater.jar --main-class com.society.updater.Main --dest "%~dp0dist" --app-version 1.0.0 --vendor Society --java-options "-Dfile.encoding=UTF-8"
if errorlevel 1 exit /b 1
copy /y "%~dp0cli.cmd" "%~dp0dist\ModpackUpdater\cli.cmd" >nul
echo Output: %~dp0dist\ModpackUpdater\ModpackUpdater.exe
echo CLI:    %~dp0dist\ModpackUpdater\cli.cmd
endlocal
