# Build HyperLauncher and install it on a TV over the network (Windows).
#   .\install.ps1 <TV_IP> [-Lite] [path\to\app.apk]   (give an APK to skip the build)
#   -Lite  builds the Lite edition: no network ADB, no INTERNET permission (see README)
# Needs: adb; and, unless you pass an APK, JDK 17 and the Android SDK (ANDROID_HOME).
param([Parameter(Mandatory = $true)][string]$Tv, [string]$Apk = "", [switch]$Lite)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw "adb not found. Install Android platform-tools and put it on PATH." }

$edition = if ($Lite) { "lite" } else { "full" }
if (-not $Apk) {
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
    $ver = (& $java -version 2>&1 | Out-String)
    if ($ver -notmatch 'version "17\.') { throw "JDK 17 is required (set JAVA_HOME to it). Newer JDKs break this Gradle version." }
    if (-not (Test-Path local.properties)) {
        $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
        if (-not $sdk) { throw "Set ANDROID_HOME to your Android SDK folder." }
        "sdk.dir=" + ($sdk -replace '\\', '/') | Set-Content -Encoding ascii local.properties
    }
    $task = if ($Lite) { "assembleLiteDebug" } else { "assembleFullDebug" }
    & .\gradlew.bat --no-daemon ":app:$task" -q
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }
    $Apk = "app\build\outputs\apk\$edition\debug\app-$edition-debug.apk"
}

$target = "${Tv}:5555"
adb connect $target | Out-Null
adb -s $target get-state *> $null
if ($LASTEXITCODE -ne 0) {
    throw "The TV did not answer on $target. Turn on Developer options > USB debugging and Network debugging, accept the prompt on the TV (tick Always allow), then run this again."
}
adb -s $target install -r $Apk
adb -s $target shell am start -n com.hyperlauncher.tv/com.hyperlauncher.control.ControlCenterActivity | Out-Null
Write-Host "Installed ($edition edition). The Control Center is open on the TV; use 'Home & Theme' > 'Customize Launcher' and Settings > 'Start HyperLauncher on start'."
