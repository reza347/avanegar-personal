@echo off
setlocal
set "ROOT=%~dp0"
set "WRAPPER=%ROOT%gradle\wrapper\gradle-wrapper.jar"
if not exist "%WRAPPER%" (
  echo Downloading official Gradle wrapper v8.11.1...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar' -OutFile '%WRAPPER%'"
  if errorlevel 1 exit /b 1
)
java -classpath "%WRAPPER%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %errorlevel%
