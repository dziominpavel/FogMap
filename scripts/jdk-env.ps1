# jdk-env.ps1 — находит рабочий JDK для Gradle/AGP и выставляет JAVA_HOME.
#
# Зачем: системный java на машине — Oracle 25, на нём Kotlin DSL Gradle падает
# (IllegalArgumentException: 25.0.2). Gradle 8.10.2 и AGP работают на 17/21.
#
# Использование:
#   . .\scripts\jdk-env.ps1          # выставить в текущей сессии
#   $env:JAVA_HOME = .\scripts\jdk-env.ps1      # или забрать путь
#
# Правила поиска (те же, что у build-apk.bat): JDK 17 в Program Files,
# затем любой JDK 17/21 из %USERPROFILE%\.gradle\jdks (25+ отсекается).

$ErrorActionPreference = 'Stop'

function Resolve-ProjectJdk {
    $candidates = @()
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        $v = & (Join-Path $env:JAVA_HOME 'bin\javac.exe') -version 2>&1
        if ($v -match '1[78]\.|2[01]\.') { $candidates += $env:JAVA_HOME }
    }
    foreach ($p in @(
        'C:\Program Files\Java\jdk-17',
        'C:\Program Files\Java\jdk-17.0.7',
        "$env:USERPROFILE\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"
    )) {
        if (Test-Path (Join-Path $p 'bin\javac.exe')) { $candidates += $p }
    }
    $gradleJdks = "$env:USERPROFILE\.gradle\jdks"
    if (Test-Path $gradleJdks) {
        foreach ($d in Get-ChildItem $gradleJdks -Directory) {
            if ($d.Name -match '-(17|21)(-|\.|$)') {
                if (Test-Path (Join-Path $d.FullName 'bin\javac.exe')) { $candidates += $d.FullName }
            }
        }
    }
    if ($candidates.Count -eq 0) {
        throw "JDK 17/21 не найден. Поставь Temurin 17 или JBR 21 и повтори."
    }
    return ($candidates | Select-Object -First 1)
}

$jdk = Resolve-ProjectJdk
$env:JAVA_HOME = $jdk
$ver = & (Join-Path $jdk 'bin\javac.exe') -version 2>&1
Write-Host "JAVA_HOME=$jdk ($ver)"
$jdk
