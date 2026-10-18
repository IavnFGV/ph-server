$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $root '.tools/wake-confirmation-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& javac -encoding UTF-8 -d $classes `
    (Join-Path $root 'android/voice-color-station/src/local/ivand/voicecolorstation/WakeConfirmation.java') `
    (Join-Path $root 'android/voice-color-station/tests/WakeConfirmationTest.java') `
    (Join-Path $root 'android/voice-color-station/src/local/ivand/voicecolorstation/WakeUiTimeoutPolicy.java') `
    (Join-Path $root 'android/voice-color-station/tests/WakeUiTimeoutPolicyTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Wake confirmation test compilation failed' }
& java -cp $classes local.ivand.voicecolorstation.WakeConfirmationTest
if ($LASTEXITCODE -ne 0) { throw 'Wake confirmation tests failed' }
& java -cp $classes local.ivand.voicecolorstation.WakeUiTimeoutPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Wake UI timeout tests failed' }
