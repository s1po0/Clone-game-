@rem Minimal Gradle wrapper launcher for MangaScribe (Windows).
@echo off
setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
if defined JAVA_HOME (
  set JAVACMD=%JAVA_HOME%\bin\java.exe
) else (
  set JAVACMD=java
)
"%JAVACMD%" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew ^
  -classpath "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" ^
  org.gradle.wrapper.GradleWrapperMain %*
endlocal
