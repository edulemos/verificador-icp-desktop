@echo off
setlocal
pushd "%~dp0"
call mvn -s scripts\maven-settings.xml -B -ntp clean package
if errorlevel 1 (
  popd
  exit /b 1
)
if not exist release mkdir release
copy /y desktop\target\verificador-desktop.jar release\verificador-desktop.jar >nul
copy /y scripts\run-release.bat release\iniciar.bat >nul
echo.
echo Aplicativo gerado em release\verificador-desktop.jar
popd
