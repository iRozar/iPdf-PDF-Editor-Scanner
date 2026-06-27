@echo off
setlocal

echo.
echo Fixing Android Studio / Gradle JDK for this project...
echo.

set "JBR=C:\Program Files\Android\Android Studio\jbr"
set "GRADLE_BAT=C:\Users\ArJun\.gradle\wrapper\dists\gradle-9.3.1-bin\23ovyewtku6u96viwx3xl3oks\gradle-9.3.1\bin\gradle.bat"

if not exist "%JBR%\bin\java.exe" (
  echo ERROR: Java was not found here:
  echo %JBR%\bin\java.exe
  echo.
  echo Check that Android Studio is installed in:
  echo C:\Program Files\Android\Android Studio
  pause
  exit /b 1
)

if not exist "%JBR%\bin\jlink.exe" (
  echo ERROR: jlink was not found here:
  echo %JBR%\bin\jlink.exe
  pause
  exit /b 1
)

echo Setting JAVA_HOME for your Windows user account...
setx JAVA_HOME "%JBR%"

echo.
echo Using JAVA_HOME for this terminal session:
set "JAVA_HOME=%JBR%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo.
echo Java version:
"%JAVA_HOME%\bin\java.exe" -version

echo.
echo Stopping old Gradle daemons...
if exist "%GRADLE_BAT%" (
  call "%GRADLE_BAT%" --stop
) else (
  echo Gradle was not found at:
  echo %GRADLE_BAT%
  echo Skipping Gradle stop.
)

echo.
echo Done.
echo.
echo IMPORTANT NEXT STEPS:
echo 1. Close Android Studio completely.
echo 2. Open Task Manager and end studio64.exe/java.exe/gradle.exe if still running.
echo 3. Reopen Android Studio.
echo 4. Click File ^> Sync Project with Gradle Files.
echo 5. Run the app.
echo.
echo If the old .vscode redhat.java jlink error still appears:
echo File ^> Invalidate Caches ^> Invalidate and Restart
echo.
pause
