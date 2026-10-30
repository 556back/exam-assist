param([ValidatePattern('^emulator-\d+$')][string]$Serial = 'emulator-5554',
    [ValidateSet('automatic', 'automatic_compatibility', 'automatic_labels', 'automatic_ai', 'bank_ai', 'page_text', 'page_text_session', 'screenshot', 'overlay')][string]$Check = 'automatic',
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')][string]$LogVersion = '1.0.0')

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $projectRoot '.tools/android-sdk/platform-tools/adb.exe'
if ($Serial -notlike 'emulator-*') { throw 'Run only on a disposable emulator.' }
$enabledBefore = (& $adb -s $Serial shell settings get secure enabled_accessibility_services).Trim()
$accessibilityBefore = (& $adb -s $Serial shell settings get secure accessibility_enabled).Trim()
$logPath = Join-Path $projectRoot ".tools/android-$Check-$LogVersion.log"
$errorPath = "$logPath.stderr"
$instrument = $null
try {
    & $adb -s $Serial install -r (Join-Path $projectRoot 'android/app/build/outputs/apk/debug/app-debug.apk')
    if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
    & $adb -s $Serial install -r (Join-Path $projectRoot 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed.' }
    & $adb -s $Serial shell appops set cn.examassist.app SYSTEM_ALERT_WINDOW allow
    & $adb -s $Serial shell pm grant cn.examassist.app android.permission.POST_NOTIFICATIONS
    $instrument = Start-Process -FilePath $adb -ArgumentList @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'check', $Check,
        'cn.examassist.app.test/cn.examassist.app.AnswerOverlayCheck') -WindowStyle Hidden -RedirectStandardOutput $logPath -RedirectStandardError $errorPath -PassThru
    # Instrumentation restarts the app; refresh only this emulator's test service binding.
    if ($Check -like 'automatic*' -or $Check -eq 'page_text') {
        $readyDeadline = (Get-Date).AddSeconds(40)
        while (-not $instrument.HasExited -and (Get-Date) -lt $readyDeadline) {
            if ((Get-Content -LiteralPath $logPath -Raw) -match 'WAITING_FOR_ACCESSIBILITY') { break }
            Start-Sleep -Milliseconds 500
        }
    } else { Start-Sleep -Seconds 4 }
    & $adb -s $Serial shell settings put secure enabled_accessibility_services null
    Start-Sleep -Milliseconds 500
    & $adb -s $Serial shell settings put secure enabled_accessibility_services cn.examassist.app/cn.examassist.app.PageTextService
    & $adb -s $Serial shell settings put secure accessibility_enabled 1
    $deadline = (Get-Date).AddSeconds(100)
    while (-not $instrument.WaitForExit(1000)) {
        if ((Get-Date) -gt $deadline) { throw 'Instrumentation timed out.' }
    }
    $log = Get-Content -LiteralPath $logPath -Raw
    Write-Output $log
    if ($log -notmatch 'PASS \(' -or $log -notmatch 'INSTRUMENTATION_CODE: -1') { throw 'Native checks failed; see the instrumentation log.' }
} finally {
    if ($instrument -and -not $instrument.HasExited) {
        & $adb -s $Serial shell am force-stop cn.examassist.app
    }
    & $adb -s $Serial shell settings put secure enabled_accessibility_services $enabledBefore
    & $adb -s $Serial shell settings put secure accessibility_enabled $accessibilityBefore
}
