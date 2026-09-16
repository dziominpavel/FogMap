@echo off
rem Build APK and copy it to dist\ so you don't dig in app\build\outputs\...
rem Usage: build-apk.bat [release / debug] [install]
rem   no args  - release build
rem   debug    - debug build (.dev applicationIdSuffix)
rem   install  - second arg, installs the resulting APK via adb
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

set "TYPE=%~1"
if "%TYPE%"=="" set "TYPE=release"
if /i "%TYPE%"=="r" set "TYPE=release"
if /i "%TYPE%"=="d" set "TYPE=debug"

if /i not "%TYPE%"=="release" if /i not "%TYPE%"=="debug" (
  echo Usage: %~nx0 [release / debug] [install]
  exit /b 2
)

if /i "%TYPE%"=="release" (
  set "TASK=assembleRelease"
  set "SRC=app\build\outputs\apk\release\app-release.apk"
) else (
  set "TASK=assembleDebug"
  set "SRC=app\build\outputs\apk\debug\app-debug.apk"
)

echo === FogMap: building %TYPE% (%TASK%) ===
call gradlew.bat %TASK% --console=plain "-Dorg.gradle.java.home=%JAVA_HOME%"
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

for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmm"') do set "TS=%%i"
set "DATED=dist\FogMap-!TYPE!-!TS!.apk"
set "LATEST=dist\FogMap-!TYPE!-latest.apk"

copy /y "%SRC%" "%DATED%" >nul
copy /y "%SRC%" "%LATEST%" >nul

echo.
echo [OK] Done:
echo   %DATED%
echo   %LATEST%  -- install this one, it is always the freshest
echo.
for %%F in ("%LATEST%") do echo   Size: %%~zF bytes

if /i "%~2"=="install" (
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
