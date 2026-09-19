@echo off
rem Build APK and copy it to dist\ so you don't dig in app\build\outputs\...
rem Usage: build-apk.bat [debug] [install]
rem   no args  - builds release for real phones (arm64-v8a + armeabi-v7a)
rem   debug    - builds debuggable APK (package ru.fogmap.dev, all ABIs) for
rem              log capture via adb run-as; installs side-by-side with release,
rem              data does NOT overlap. Debug is for investigation only,
rem              not for everyday tracking.
rem   install  - also installs the APK via adb (may follow the debug word)
rem Result: dist\FogMap-release-latest.apk or dist\FogMap-debug-latest.apk
rem (history copies in dist\archive\).
rem ABI filtering is packaging-only: nothing is cut from the project,
rem MapKit stays whole (-PtargetAbis passed to Gradle, see app/build.gradle.kts).
rem Debug builds skip ABI filtering so they also work on the emulator (x86_64).
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

set "MODE=release"
set "INSTALL=0"
if /i "%~1"=="debug" set "MODE=debug"
if /i "%~1"=="install" set "INSTALL=1"
if /i "%~2"=="install" set "INSTALL=1"
if not "%~1"=="" if /i not "%~1"=="debug" if /i not "%~1"=="install" (
  echo Usage: %~nx0 [debug] [install]
  exit /b 2
)
if not "%~2"=="" if /i not "%~2"=="install" (
  echo Usage: %~nx0 [debug] [install]
  exit /b 2
)
if "%~1"=="" if not "%~2"=="" (
  echo Usage: %~nx0 [debug] [install]
  exit /b 2
)

set "TASK=assembleRelease"
set "SRC=app\build\outputs\apk\release\app-release.apk"
set "EXTRA=-PtargetAbis=arm64-v8a,armeabi-v7a"
set "TAGNAME=release"
if "%MODE%"=="debug" (
  set "TASK=assembleDebug"
  set "SRC=app\build\outputs\apk\debug\app-debug.apk"
  set "EXTRA="
  set "TAGNAME=debug"
)

echo === FogMap: building %MODE% (%TASK%) %EXTRA% ===
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
set "DATED=dist\archive\FogMap-!TAGNAME!-!TS!.apk"
set "LATEST=dist\FogMap-!TAGNAME!-latest.apk"

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
