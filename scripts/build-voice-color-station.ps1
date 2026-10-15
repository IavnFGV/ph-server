[CmdletBinding()]
param(
    [string]$AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$appRoot = Join-Path $projectRoot 'android\voice-color-station'
$buildRoot = Join-Path $appRoot 'build'
$artifacts = Join-Path $appRoot 'artifacts'
$toolState = Join-Path $projectRoot '.tools\voice-color-station'
$logDirectory = Join-Path $projectRoot 'logs\windows'
$logPath = Join-Path $logDirectory 'voice-color-station-build.log'

New-Item -ItemType Directory -Force -Path $logDirectory,$artifacts,$toolState | Out-Null
Start-Transcript -Path $logPath -Append | Out-Null

try {
    $buildTools = Join-Path $AndroidSdk 'build-tools\34.0.0'
    $androidJar = Join-Path $AndroidSdk 'platforms\android-34\android.jar'
    $aapt2 = Join-Path $buildTools 'aapt2.exe'
    $d8 = Join-Path $buildTools 'd8.bat'
    $zipalign = Join-Path $buildTools 'zipalign.exe'
    $apksigner = Join-Path $buildTools 'apksigner.bat'
    foreach ($required in @($androidJar,$aapt2,$d8,$zipalign,$apksigner)) {
        if (-not (Test-Path -LiteralPath $required)) { throw "Missing build dependency: $required" }
    }

    if (Test-Path -LiteralPath $buildRoot) {
        $resolvedBuild = [IO.Path]::GetFullPath($buildRoot)
        $resolvedApp = [IO.Path]::GetFullPath($appRoot)
        if (-not $resolvedBuild.StartsWith($resolvedApp + [IO.Path]::DirectorySeparatorChar,
                [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to clean build directory outside app root: $resolvedBuild"
        }
        [IO.Directory]::Delete($resolvedBuild, $true)
    }
    $classes = New-Item -ItemType Directory -Force -Path (Join-Path $buildRoot 'classes')
    $dex = New-Item -ItemType Directory -Force -Path (Join-Path $buildRoot 'dex')

    $resourcesApk = Join-Path $buildRoot 'resources.apk'
    & $aapt2 link -o $resourcesApk -I $androidJar `
        --manifest (Join-Path $appRoot 'AndroidManifest.xml') `
        --min-sdk-version 31 --target-sdk-version 31 `
        --version-code 5 --version-name '0.3.2'
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed: $LASTEXITCODE" }

    $sourceRoot = Join-Path $appRoot 'src\local\ivand\voicecolorstation'
    $sources = Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -File |
        Select-Object -ExpandProperty FullName
    if (-not $sources) { throw "No Java sources found in: $sourceRoot" }
    & javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $androidJar `
        -d $classes.FullName $sources
    if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }

    $classesJar = Join-Path $buildRoot 'classes.jar'
    & jar cf $classesJar -C $classes.FullName .
    if ($LASTEXITCODE -ne 0) { throw "Packing compiled classes failed: $LASTEXITCODE" }

    & $d8 --lib $androidJar --min-api 31 --output $dex.FullName $classesJar
    if ($LASTEXITCODE -ne 0) { throw "d8 failed: $LASTEXITCODE" }

    $withDex = Join-Path $buildRoot 'with-dex.apk'
    Copy-Item -LiteralPath $resourcesApk -Destination $withDex
    & jar uf $withDex -C $dex.FullName classes.dex
    if ($LASTEXITCODE -ne 0) { throw "Adding classes.dex failed: $LASTEXITCODE" }

    $aligned = Join-Path $buildRoot 'aligned.apk'
    & $zipalign -f 4 $withDex $aligned
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed: $LASTEXITCODE" }

    $keystore = Join-Path $toolState 'debug.keystore'
    if (-not (Test-Path -LiteralPath $keystore)) {
        & keytool -genkeypair -noprompt -keystore $keystore `
            -storepass android -alias androiddebugkey -keypass android `
            -dname 'CN=Android Debug,O=Local Development,C=UA' `
            -keyalg RSA -keysize 2048 -validity 10000
        if ($LASTEXITCODE -ne 0) { throw "keytool failed: $LASTEXITCODE" }
    }

    $outputApk = Join-Path $artifacts 'voice-color-station-debug.apk'
    & $apksigner sign --ks $keystore --ks-pass pass:android `
        --key-pass pass:android --out $outputApk $aligned
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed: $LASTEXITCODE" }
    & $apksigner verify --verbose --print-certs $outputApk
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $LASTEXITCODE" }

    Get-FileHash -Algorithm SHA256 -LiteralPath $outputApk |
        Select-Object Path,Algorithm,Hash
}
finally {
    Stop-Transcript | Out-Null
}
