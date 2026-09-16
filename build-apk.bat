@echo off
rem Build APK and copy it to dist\ so you don't dig in app\build\outputs\...
rem Usage: build-apk.bat [install]
rem   no args  - builds release for real phones (arm64-v8a + armeabi-v7a)
rem   install  - also installs the APK via adb
rem Result: dist\FogMap-release-latest.apk (history copies in dist\archive\).
rem No debug/emulator options here: the owner has no emulator.
rem If a debug/emulator build is ever needed, run gradlew directly.
rem ABI filtering is packaging-only: nothing is cut from the project,
rem MapKit stays whole (-PtargetAbis passed to Gradle, see app/build.gradle.kts).
rem APK itself is NOT committed to git: already excluded via *.apk in .gitignore.
setlocal EnableDelayedExpansion
cd /d "%~dp0"

rem --- Portable JDK 17 selection: works on home + work PCs ---
rem gradle.properties intentionally has no org.gradle.java.home (machine-specific path).
rem Prefer JDK 17 when found, otherwise keep the existing JAVA_HOME.
set "CANDIDATE_JDK="
if exist "C:\Program Files\Java\jdk-17\bin\java.exe" set "CANDIDATE_JDK=C:\Program Files\Java\jdk-17"
if not defined CANDIDATE_JDK if exist "C:\Program Files\Java\jdk-17.0.7\bin\java.exe" set "CANDIDATE_JDK=C:\Program Files\Java\jdk-17.0.7"
if not defined CANDIDATE_JDK if exist "%USERPROFILE%\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2\bin\java.exe" set "CANDIDATE_JDK=%USERPROFILE%\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"
if defined CANDIDATE_JDK set "JAVA_HOME=%CANDIDATE_JDK%"
echo Using JAVA_HOME=%JAVA_HOME%
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] No JDK found. Set JAVA_HOME to a JDK 17+ or install one.
  pause
  exit /b 1
)

set "INSTALL=0"
if /i "%~1"=="install" set "INSTALL=1"
if not "%~1"=="" if /i not "%~1"=="install" (
  echo Usage: %~nx0 [install]
  exit /b 2
)

set "TASK=assembleRelease"
set "SRC=app\build\outputs\apk\release\app-release.apk"
set "EXTRA=-PtargetAbis=arm64-v8a,armeabi-v7a"

echo === FogMap: building release (%TASK%) %EXTRA% ===
call gradlew.bat %TASK% --console=plain "-Dorg.gradle.java.home=%JAVA_HOME%" %EXTRA%
if errorlevel 1 (
  echo.
  echo [ERROR] Build failed. See the log above.
  pause
  exit /b 1
)

if not exist "%SRC%" (
  echo [ERROR] Build reported success but file not found: %SRC%
  pause
  exit /b 1
)

if not exist "dist" mkdir "dist"
if not exist "dist\archive" mkdir "dist\archive"

for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmm"') do set "TS=%%i"
set "DATED=dist\archive\FogMap-release-!TS!.apk"
set "LATEST=dist\FogMap-release-latest.apk"

copy /y "%SRC%" "%DATED%" >nul
copy /y "%SRC%" "%LATEST%" >nul

echo.
echo [OK] Done:
echo   TAKE THIS: %LATEST%  -- install it on the phone
echo   (history copy: %DATED%)
echo.
for %%F in ("%LATEST%") do echo   Size: %%~zF bytes

if "%INSTALL%"=="1" (
  echo.
  echo === Installing on device ===
  where adb >nul 2>&1
  if errorlevel 1 (
    echo [ERROR] adb not found in PATH. Open the project in Android Studio or add platform-tools to PATH.
    pause
    exit /b 1
  )
  adb install -r "%LATEST%"
  if errorlevel 1 (
    echo [ERROR] Install failed. Check the device is connected: adb devices
    pause
    exit /b 1
  )
  echo [OK] Installed.
)

echo.
pause
endlocal
