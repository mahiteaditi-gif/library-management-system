@echo off
rem Compiles and starts Library Management System (needs JDK 17 or newer on PATH)
cd /d "%~dp0"
if not exist out mkdir out
javac -d out src\lms\*.java
if errorlevel 1 (
  echo.
  echo Compile failed. Check that the JDK is installed: run "javac -version"
  pause
  exit /b 1
)
echo.
echo Starting... open http://localhost:8080 in your browser
java -cp "out;lib\sqlite-jdbc.jar" lms.Main
pause
