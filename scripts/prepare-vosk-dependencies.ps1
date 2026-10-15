[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$stateRoot = Join-Path $projectRoot '.tools\vosk'
$downloads = Join-Path $stateRoot 'downloads'
$expanded = Join-Path $stateRoot 'expanded'
$assets = Join-Path $stateRoot 'assets'
$logDirectory = Join-Path $projectRoot 'logs\windows'
$logPath = Join-Path $logDirectory 'vosk-dependency-prepare.log'

$dependencies = @(
    @{
        Name = 'vosk-android-0.3.75.aar'
        Url = 'https://repo1.maven.org/maven2/com/alphacephei/vosk-android/0.3.75/vosk-android-0.3.75.aar'
        Sha256 = 'AB2F8B91AC8051561AA325546B35FED9A68B36B8121BAC5C6FB927525C4ADFAD'
    },
    @{
        Name = 'jna-5.18.1.aar'
        Url = 'https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.18.1/jna-5.18.1.aar'
        Sha256 = '7F053E3EC99E14DD71259C82C1C8A02738D64A13C31226B2ACC170F3060951E0'
    },
    @{
        Name = 'vosk-model-small-ru-0.22.zip'
        Url = 'https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip'
        Sha256 = '961D5FF98A17F4AA6DE69864D0AA71FA5BAC682301D2B5D17A3F24C5C99A46D4'
    },
    @{
        Name = 'r8-9.5.26.jar'
        Url = 'https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.5.26/r8-9.5.26.jar'
        Sha256 = '3502F087388CE95BBF5254555927F09385DC2636873964D0BD53F3EAF4FB49AD'
    }
)

function Assert-ChildPath([string]$Path, [string]$Parent) {
    $resolvedPath = [IO.Path]::GetFullPath($Path)
    $resolvedParent = [IO.Path]::GetFullPath($Parent)
    if (-not $resolvedPath.StartsWith(
            $resolvedParent + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing operation outside dependency state root: $resolvedPath"
    }
}

function Get-VerifiedDependency([hashtable]$Dependency) {
    $destination = Join-Path $downloads $Dependency.Name
    if (Test-Path -LiteralPath $destination) {
        $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash
        if ($actual -ne $Dependency.Sha256) {
            throw "Checksum mismatch for existing $($Dependency.Name): $actual"
        }
        Write-Host "Verified cached $($Dependency.Name): $actual"
        return $destination
    }

    Write-Host "Downloading $($Dependency.Url)"
    Invoke-WebRequest -Uri $Dependency.Url -OutFile $destination
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash
    if ($actual -ne $Dependency.Sha256) {
        Remove-Item -LiteralPath $destination -Force
        throw "Checksum mismatch for downloaded $($Dependency.Name): $actual"
    }
    Write-Host "Verified downloaded $($Dependency.Name): $actual"
    return $destination
}

New-Item -ItemType Directory -Force -Path $stateRoot,$downloads,$logDirectory |
    Out-Null
Start-Transcript -Path $logPath -Append | Out-Null

try {
    $resolvedFiles = @{}
    foreach ($dependency in $dependencies) {
        $resolvedFiles[$dependency.Name] = Get-VerifiedDependency $dependency
    }

    if ($Force -and (Test-Path -LiteralPath $expanded)) {
        Assert-ChildPath $expanded $stateRoot
        Remove-Item -LiteralPath $expanded -Recurse -Force
    }
    if ($Force -and (Test-Path -LiteralPath $assets)) {
        Assert-ChildPath $assets $stateRoot
        Remove-Item -LiteralPath $assets -Recurse -Force
    }

    foreach ($name in @('vosk','jna')) {
        $target = Join-Path $expanded $name
        if (-not (Test-Path -LiteralPath (Join-Path $target 'classes.jar'))) {
            New-Item -ItemType Directory -Force -Path $target | Out-Null
            $sourceName = if ($name -eq 'vosk') {
                'vosk-android-0.3.75.aar'
            } else {
                'jna-5.18.1.aar'
            }
            $temporaryZip = Join-Path $stateRoot "$name.zip"
            Copy-Item -LiteralPath $resolvedFiles[$sourceName] `
                -Destination $temporaryZip -Force
            Expand-Archive -LiteralPath $temporaryZip -DestinationPath $target -Force
            Remove-Item -LiteralPath $temporaryZip -Force
        }
    }

    $modelDirectory = Join-Path $assets 'vosk-model-small-ru-0.22'
    if (-not (Test-Path -LiteralPath (Join-Path $modelDirectory 'am\final.mdl'))) {
        New-Item -ItemType Directory -Force -Path $assets | Out-Null
        Expand-Archive -LiteralPath `
            $resolvedFiles['vosk-model-small-ru-0.22.zip'] `
            -DestinationPath $assets -Force
    }

    $uuidPath = Join-Path $modelDirectory 'uuid'
    Set-Content -LiteralPath $uuidPath -Encoding utf8NoBOM -NoNewline `
        -Value 'vosk-model-small-ru-0.22-961d5ff98a17f4aa'

    foreach ($required in @(
        (Join-Path $expanded 'vosk\classes.jar'),
        (Join-Path $expanded 'vosk\jni\arm64-v8a\libvosk.so'),
        (Join-Path $expanded 'jna\classes.jar'),
        (Join-Path $expanded 'jna\jni\arm64-v8a\libjnidispatch.so'),
        (Join-Path $modelDirectory 'graph\Gr.fst'),
        $resolvedFiles['r8-9.5.26.jar'],
        $uuidPath
    )) {
        if (-not (Test-Path -LiteralPath $required)) {
            throw "Prepared dependency file is missing: $required"
        }
    }

    Write-Output "Vosk dependencies are ready in $stateRoot"
}
finally {
    Stop-Transcript | Out-Null
}
