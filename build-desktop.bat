@echo off
rem Smart Service Hub -> дербес Windows апп (Java орнатпай-ақ жұмыс істейді).
rem Нәтиже: dist\SmartServiceHub\SmartServiceHub.exe
setlocal

where mvn >nul 2>nul
if %errorlevel%==0 (
  call mvn -q clean package -DskipTests
  if errorlevel 1 ( echo Maven жинау қатесі & pause & exit /b 1 )
) else (
  echo mvn PATH-та жоқ, дайын target\smart-service-hub-1.0.0.jar қолданылады.
)
if not exist target\smart-service-hub-1.0.0.jar (
  echo jar жоқ. IntelliJ: Maven панелі - Lifecycle - package басыңыз, сосын осы файлды қайта іске қосыңыз.
  pause
  exit /b 1
)

set "JP=jpackage"
set "RT="
if defined JAVA_HOME (
  set "JP=%JAVA_HOME%\bin\jpackage.exe"
  set "RT=%JAVA_HOME%"
) else if exist "C:\Program Files\Java\jdk-27\bin\jpackage.exe" (
  set "JP=C:\Program Files\Java\jdk-27\bin\jpackage.exe"
  set "RT=C:\Program Files\Java\jdk-27"
)
if "%RT%"=="" ( echo JAVA_HOME орнатылмаған. jpackage және JDK жолын тексеріңіз. & pause & exit /b 1 )

if exist dist rmdir /s /q dist
mkdir dist\input
copy /y target\smart-service-hub-1.0.0.jar dist\input\ >nul

"%JP%" --type app-image --name SmartServiceHub --dest dist --input dist\input ^
  --main-jar smart-service-hub-1.0.0.jar ^
  --main-class org.springframework.boot.loader.launch.JarLauncher ^
  --arguments "--desktop" ^
  --runtime-image "%RT%" ^
  --java-options "-Dnet.bytebuddy.experimental=true"
if errorlevel 1 ( echo jpackage қатесі & pause & exit /b 1 )

echo.
echo Дайын: dist\SmartServiceHub\SmartServiceHub.exe
pause
