@echo off
rem Тез іске қосу (Java орнатылған болуы керек). Алдымен jar жиналған болуы керек: target\smart-service-hub-1.0.0.jar
if not exist target\smart-service-hub-1.0.0.jar (
  echo jar табылмады. IntelliJ: Maven панелі - Lifecycle - package басыңыз.
  pause
  exit /b 1
)
java -jar target\smart-service-hub-1.0.0.jar --desktop
