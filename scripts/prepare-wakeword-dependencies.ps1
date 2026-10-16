[CmdletBinding()]
param([ValidateSet('generic','personal-v2')][string]$WakeProfile = 'generic')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$state = Join-Path $root '.tools\wakeword'
$downloads = Join-Path $state 'downloads'
$assets = Join-Path $state 'assets\wakeword'
$expanded = Join-Path $state 'expanded'
$log = Join-Path $root 'logs\windows\wakeword-dependency-prepare.log'
New-Item -ItemType Directory -Force $downloads,$assets,$expanded | Out-Null
Start-Transcript -Path $log -Append | Out-Null
try {
    $dependencies = @(
        @{Name='onnxruntime-android-1.22.0.aar'; Hash='04A4617A9C797CF49225595E45B5546081CB34C86AC817581141577D3B7DBFE2'; Url='https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.22.0/onnxruntime-android-1.22.0.aar'},
        @{Name='embedding_model.onnx'; Hash='70D164290C1D095D1D4EE149BC5E00543250A7316B59F31D056CFF7BD3075C1F'; Url='https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.onnx'},
        @{Name='melspectrogram.onnx'; Hash='BA2B0E0F8B7B875369A2C89CB13360FF53BAC436F2895CCED9F479FA65EB176F'; Url='https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.onnx'}
    )
    foreach ($item in $dependencies) {
        $file = Join-Path $downloads $item.Name
        if (-not (Test-Path -LiteralPath $file)) { Invoke-WebRequest $item.Url -OutFile $file }
        $hash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
        if ($hash -ne $item.Hash) { throw "Checksum mismatch: $file ($hash)" }
        Write-Host "Verified $($item.Name): $hash"
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $ortDirectory = Join-Path $expanded 'ort'
    if (-not (Test-Path -LiteralPath (Join-Path $ortDirectory 'classes.jar'))) {
        [IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $downloads 'onnxruntime-android-1.22.0.aar'), $ortDirectory)
    }
    foreach ($name in @('embedding_model.onnx','melspectrogram.onnx')) {
        Copy-Item -LiteralPath (Join-Path $downloads $name) -Destination $assets
    }
    $profiles = Get-Content -Raw -Encoding UTF8 (Join-Path $root 'training/pomogator/android-profiles.json') | ConvertFrom-Json
    $profile = $profiles.$WakeProfile
    $model = Join-Path $root $profile.model_path
    $hash = (Get-FileHash -LiteralPath $model -Algorithm SHA256).Hash
    if ($hash -ne $profile.model_sha256) {
        throw "Unexpected Pomogator model SHA-256: $hash"
    }
    Copy-Item -LiteralPath $model -Destination (Join-Path $assets 'pomogator.onnx')
    $config = [ordered]@{profile=$WakeProfile; model_sha256=$hash; threshold=$profile.threshold;
        phrase=$profile.phrase; self_test_score=$profile.self_test_score; self_test_feature=$profile.self_test_feature}
    $config | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $assets 'config.json') -Encoding utf8NoBOM
    Write-Host "Wake profile=$WakeProfile; model=$hash; threshold=$($profile.threshold)"
    Write-Host "Prepared wakeword assets and ARM64 ONNX Runtime"
} finally { Stop-Transcript | Out-Null }
