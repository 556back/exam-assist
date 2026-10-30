param(
    [string]$SdkPath,
    [string[]]$Tasks = @("assembleDebug")
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

if (-not $SdkPath) {
    $SdkPath = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $projectRoot ".tools\android-sdk"),
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ "platforms\android-35\android.jar")) } | Select-Object -First 1
}

if (-not $SdkPath -or -not (Test-Path -LiteralPath (Join-Path $SdkPath "build-tools\35.0.0\aapt2.exe"))) {
    throw "Android SDK 35 and Build Tools 35.0.0 are required. Install them with Android Studio or sdkmanager after reviewing and accepting the Android SDK license, then pass -SdkPath <directory>."
}

$previousSdk = $env:ANDROID_HOME
$previousSdkRoot = $env:ANDROID_SDK_ROOT
$previousGradleHome = $env:GRADLE_USER_HOME
$previousAndroidUserHome = $env:ANDROID_USER_HOME
$gradle = Join-Path $projectRoot ".tools\gradle-8.11.1\bin\gradle.bat"
if (-not (Test-Path -LiteralPath $gradle)) {
    $gradle = Join-Path $projectRoot "android\gradlew.bat"
}

try {
    $env:ANDROID_HOME = (Resolve-Path -LiteralPath $SdkPath).Path
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $env:GRADLE_USER_HOME = Join-Path $projectRoot ".tools\gradle-home"
    $env:ANDROID_USER_HOME = Join-Path $projectRoot ".tools\android-user-home"
    & $gradle -p (Join-Path $projectRoot "android") @Tasks --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Android Gradle build failed (exit $LASTEXITCODE)."
    }
} finally {
    $env:ANDROID_HOME = $previousSdk
    $env:ANDROID_SDK_ROOT = $previousSdkRoot
    $env:GRADLE_USER_HOME = $previousGradleHome
    $env:ANDROID_USER_HOME = $previousAndroidUserHome
}
