[CmdletBinding()]
param(
    [string]$AndroidSdk = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    [ValidateSet('generic','personal-v2')][string]$WakeProfile = 'generic'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$appRoot = Join-Path $projectRoot 'android\voice-color-station'
$buildRoot = Join-Path $appRoot 'build'
$artifacts = Join-Path $appRoot 'artifacts'
$toolState = Join-Path $projectRoot '.tools\voice-color-station'
$logDirectory = Join-Path $projectRoot 'logs\windows'
$logPath = Join-Path $logDirectory 'voice-color-station-build.log'

& (Join-Path $PSScriptRoot 'prepare-vosk-dependencies.ps1')
& (Join-Path $PSScriptRoot 'prepare-wakeword-dependencies.ps1') -WakeProfile $WakeProfile

New-Item -ItemType Directory -Force -Path $logDirectory,$artifacts,$toolState | Out-Null
Start-Transcript -Path $logPath -Append | Out-Null

try {
    $buildTools = Join-Path $AndroidSdk 'build-tools\34.0.0'
    $androidJar = Join-Path $AndroidSdk 'platforms\android-34\android.jar'
    $aapt2 = Join-Path $buildTools 'aapt2.exe'
    $zipalign = Join-Path $buildTools 'zipalign.exe'
    $apksigner = Join-Path $buildTools 'apksigner.bat'
    $voskRoot = Join-Path $projectRoot '.tools\vosk'
    $voskClasses = Join-Path $voskRoot 'expanded\vosk\classes.jar'
    $jnaClasses = Join-Path $voskRoot 'expanded\jna\classes.jar'
    $voskNative = Join-Path $voskRoot 'expanded\vosk\jni\arm64-v8a\libvosk.so'
    $jnaNative = Join-Path $voskRoot 'expanded\jna\jni\arm64-v8a\libjnidispatch.so'
    $voskAssets = Join-Path $voskRoot 'assets'
    $r8 = Join-Path $voskRoot 'downloads\r8-9.5.26.jar'
    $wakeRoot = Join-Path $projectRoot '.tools\wakeword'
    $ortClasses = Join-Path $wakeRoot 'expanded\ort\classes.jar'
    foreach ($required in @($androidJar,$aapt2,$r8,$zipalign,$apksigner,
            $voskClasses,$jnaClasses,$voskNative,$jnaNative,$voskAssets)) {
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
    $compiledResources = Join-Path $buildRoot 'compiled-resources.zip'
    & $aapt2 compile --dir (Join-Path $appRoot 'res') -o $compiledResources
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed: $LASTEXITCODE" }
    & $aapt2 link -o $resourcesApk -I $androidJar `
        -R $compiledResources `
        --manifest (Join-Path $appRoot 'AndroidManifest.xml') `
        --min-sdk-version 31 --target-sdk-version 31 `
        --version-code 15 --version-name '0.7.2'
    if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed: $LASTEXITCODE" }

    & jar uf $resourcesApk -C $voskRoot assets
    if ($LASTEXITCODE -ne 0) { throw "Adding Vosk model assets failed: $LASTEXITCODE" }
    & jar uf $resourcesApk -C $wakeRoot assets
    if ($LASTEXITCODE -ne 0) { throw "Adding wakeword model assets failed: $LASTEXITCODE" }

    $sourceRoot = Join-Path $appRoot 'src\local\ivand\voicecolorstation'
    $sources = Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -File |
        Select-Object -ExpandProperty FullName
    if (-not $sources) { throw "No Java sources found in: $sourceRoot" }
    & javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $androidJar `
        -classpath "$voskClasses;$jnaClasses;$ortClasses" -d $classes.FullName $sources
    if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }

    $classesJar = Join-Path $buildRoot 'classes.jar'
    & jar cf $classesJar -C $classes.FullName .
    if ($LASTEXITCODE -ne 0) { throw "Packing compiled classes failed: $LASTEXITCODE" }

    & java -cp $r8 com.android.tools.r8.D8 `
        --lib $androidJar --min-api 31 --output $dex.FullName `
        $classesJar $voskClasses $jnaClasses $ortClasses
    if ($LASTEXITCODE -ne 0) { throw "d8 failed: $LASTEXITCODE" }

    $withDex = Join-Path $buildRoot 'with-dex.apk'
    Copy-Item -LiteralPath $resourcesApk -Destination $withDex
    & jar uf $withDex -C $dex.FullName .
    if ($LASTEXITCODE -ne 0) { throw "Adding classes.dex failed: $LASTEXITCODE" }

    $nativeDirectory = Join-Path $buildRoot 'native\lib\arm64-v8a'
    New-Item -ItemType Directory -Force -Path $nativeDirectory | Out-Null
    Copy-Item -LiteralPath $voskNative,$jnaNative -Destination $nativeDirectory
    Copy-Item -Path (Join-Path $wakeRoot 'expanded\ort\jni\arm64-v8a\*.so') -Destination $nativeDirectory
    & jar uf $withDex -C (Join-Path $buildRoot 'native') lib
    if ($LASTEXITCODE -ne 0) { throw "Adding ARM64 libraries failed: $LASTEXITCODE" }

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
    Copy-Item -LiteralPath $outputApk -Destination (Join-Path $artifacts "voice-color-station-0.7.2-$WakeProfile.apk")
    Write-Host "Build profile=$WakeProfile; version=0.7.2; versionCode=15"
}
finally {
    Stop-Transcript | Out-Null
}
