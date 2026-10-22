@echo off
setlocal
set "APP_JAR=%~dp0release\verificador-desktop.jar"
if not exist "%APP_JAR%" set "APP_JAR=%~dp0desktop\target\verificador-desktop.jar"
if not exist "%APP_JAR%" (
  echo Compile o projeto primeiro executando build.bat.
  pause
  exit /b 1
)
java -Xmx1024m -jar "%APP_JAR%"
if errorlevel 1 pause
