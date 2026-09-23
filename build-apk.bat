@echo off
rem Build APK and copy it to dist\ so you don't dig in app\build\outputs\...
rem Usage: build-apk.bat [debug] [install] [release]
rem   no args  - builds release for real phones (arm64-v8a + armeabi-v7a)
rem   debug    - builds debuggable APK (package ru.fogmap.dev, all ABIs) for
rem              log capture via adb run-as; installs side-by-side with release,
rem              data does NOT overlap. Debug is for investigation only,
rem              not for everyday tracking.
rem   install  - also installs the APK via adb (may follow the debug word)
rem   release  - after a successful build, publish dist\ as a GitHub release
rem              via release.bat (tag vX.Y.Z from the file `version`)
rem Result: dist\FogMap-release-latest.apk ("take this") + versioned history
rem copy in dist\archive\. Full version comes from Gradle
rem (file `version` + git count/sha, see app/build.gradle.kts), e.g.
rem dist\archive\FogMap-1.0.0.13-g06885fb-dirty-20260920-0026-release.apk
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
set "RELEASE=0"
if /i "%~1"=="debug" set "MODE=debug"
if /i "%~1"=="install" set "INSTALL=1"
if /i "%~1"=="release" set "RELEASE=1"
if /i "%~2"=="install" set "INSTALL=1"
if /i "%~2"=="release" set "RELEASE=1"
if /i "%~3"=="release" set "RELEASE=1"
if not "%~1"=="" if /i not "%~1"=="debug" if /i not "%~1"=="install" if /i not "%~1"=="release" (
  echo Usage: %~nx0 [debug] [install] [release]
  exit /b 2
)
if not "%~2"=="" if /i not "%~2"=="debug" if /i not "%~2"=="install" if /i not "%~2"=="release" (
  echo Usage: %~nx0 [debug] [install] [release]
  exit /b 2
)
if not "%~3"=="" if /i not "%~3"=="debug" if /i not "%~3"=="install" if /i not "%~3"=="release" (
  echo Usage: %~nx0 [debug] [install] [release]
  exit /b 2
)
if "%~1"=="" if not "%~2"=="" (
  echo Usage: %~nx0 [debug] [install] [release]
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

rem --- Full version: single source of truth is Gradle (file `version` + git) ---
set "FULLVER="
set "VERCODE="
rem NOTE: no %EXTRA% here on purpose: -PtargetAbis contains a comma which
rem splits the for /f command (gradle would see 'arm64-v8a' as a task).
rem printFullVersion does not need ABI filtering anyway.
for /f "tokens=1* delims==" %%A in ('call gradlew.bat -q :app:printFullVersion --console=plain "-Dorg.gradle.java.home=%JAVA_HOME%" 2^>nul') do (
  if "%%A"=="FULL_VERSION" set "FULLVER=%%B"
  if "%%A"=="VERSION_CODE" set "VERCODE=%%B"
)
if not defined FULLVER (
  echo [WARN] Could not read version from Gradle, fallback to timestamp naming.
  for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmm"') do set "TS=%%i"
  set "FULLVER=0.0.0-dev-!TS!"
  set "VERCODE=?"
)
set "ARCHIVED=dist\archive\FogMap-!FULLVER!-!TAGNAME!.apk"
set "LATEST=dist\FogMap-!TAGNAME!-latest.apk"

copy /y "%SRC%" "%ARCHIVED%" >nul
copy /y "%SRC%" "%LATEST%" >nul

echo.
echo [OK] Done: FogMap !FULLVER! (versionCode !VERCODE!)
echo   TAKE THIS: %LATEST%  -- install it on the phone
echo   (history copy: %ARCHIVED%)
echo.
for %%F in ("%LATEST%") do echo   Size: %%~zF bytes

if "%INSTALL%"=="1" (
  echo.
  echo === Installing FogMap !FULLVER! ^(!VERCODE!^) on device ===
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
  echo [OK] Installed. Verifying version on device:
  if "%MODE%"=="debug" (
    adb shell dumpsys package ru.fogmap.dev | findstr /c:"versionName" /c:"versionCode"
  ) else (
    adb shell dumpsys package ru.fogmap | findstr /c:"versionName" /c:"versionCode"
  )
)

if "%RELEASE%"=="1" (
  echo.
  echo === Publishing GitHub release via release.bat ===
  call "%~dp0release.bat"
  if errorlevel 1 (
    echo.
    echo [ERROR] Release failed. See the log above.
    pause
    exit /b 1
  )
)

echo.
pause
endlocal
