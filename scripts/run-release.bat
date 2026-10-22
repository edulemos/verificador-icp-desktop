@echo off
setlocal
if not exist "%~dp0verificador-desktop.jar" (
  echo Mantenha iniciar.bat na mesma pasta que verificador-desktop.jar.
  pause
  exit /b 1
)
java -Xmx1024m -jar "%~dp0verificador-desktop.jar"
if errorlevel 1 pause
