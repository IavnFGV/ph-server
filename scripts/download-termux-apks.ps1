[CmdletBinding()]
param(
    [string]$TermuxTag = 'v0.118.3',
    [string]$BootTag = 'v0.8.1',
    [string]$Destination = (Join-Path $PSScriptRoot '..\.tools\termux-apks')
)

$ErrorActionPreference = 'Stop'
$headers = @{ 'User-Agent' = 'lg-v50s-home-server-bootstrap' }

function Get-OfficialRelease {
    param(
        [Parameter(Mandatory)] [string]$Repository,
        [Parameter(Mandatory)] [string]$Tag
    )

    $uri = "https://api.github.com/repos/$Repository/releases/tags/$Tag"
    $release = Invoke-RestMethod -Headers $headers -Uri $uri
    if ($release.draft -or $release.prerelease) {
        throw "$Repository $Tag is not a stable published release."
    }
    if ($release.html_url -notlike "https://github.com/$Repository/releases/tag/*") {
        throw "Unexpected release origin: $($release.html_url)"
    }
    return $release
}

function Save-ReleaseAsset {
    param(
        [Parameter(Mandatory)] $Release,
        [Parameter(Mandatory)] [string]$AssetName
    )

    $asset = @($Release.assets | Where-Object name -eq $AssetName)
    if ($asset.Count -ne 1) {
        throw "Expected exactly one asset named '$AssetName'; found $($asset.Count)."
    }
    if ($asset[0].browser_download_url -notlike 'https://github.com/*/releases/download/*') {
        throw "Unexpected download origin: $($asset[0].browser_download_url)"
    }

    $path = Join-Path $Destination $AssetName
    Invoke-WebRequest -Headers $headers -Uri $asset[0].browser_download_url -OutFile $path
    return $path
}

function Assert-OfficialChecksum {
    param(
        [Parameter(Mandatory)] [string]$FilePath,
        [Parameter(Mandatory)] [string]$ChecksumPath
    )

    $name = Split-Path $FilePath -Leaf
    $pattern = '(?i)^([0-9a-f]{{64}})\s+\*?{0}$' -f [regex]::Escape($name)
    $line = Get-Content -LiteralPath $ChecksumPath |
        Where-Object { $_ -match $pattern } |
        Select-Object -First 1
    if (-not $line) {
        throw "No SHA-256 entry for '$name' in official checksum file."
    }

    $expected = ([regex]::Match($line, '^[0-9a-fA-F]{64}')).Value.ToUpperInvariant()
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $FilePath).Hash.ToUpperInvariant()
    if ($actual -ne $expected) {
        throw "SHA-256 mismatch for '$name'. Expected $expected, got $actual."
    }
    [pscustomobject]@{
        File = $name
        Bytes = (Get-Item -LiteralPath $FilePath).Length
        SHA256 = $actual
        VerifiedAgainst = (Split-Path $ChecksumPath -Leaf)
    }
}

New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$Destination = (Resolve-Path -LiteralPath $Destination).Path

$termuxRelease = Get-OfficialRelease -Repository 'termux/termux-app' -Tag $TermuxTag
$termuxApkName = "termux-app_$TermuxTag+github-debug_universal.apk"
$termuxSumsName = "termux-app_$TermuxTag+github-debug_sha256sums"
$termuxApk = Save-ReleaseAsset -Release $termuxRelease -AssetName $termuxApkName
$termuxSums = Save-ReleaseAsset -Release $termuxRelease -AssetName $termuxSumsName

$bootRelease = Get-OfficialRelease -Repository 'termux/termux-boot' -Tag $BootTag
$bootApkName = "termux-boot-app_$BootTag+github.debug.apk"
$bootSumsName = 'checksums-sha256.txt'
$bootApk = Save-ReleaseAsset -Release $bootRelease -AssetName $bootApkName
$bootSums = Save-ReleaseAsset -Release $bootRelease -AssetName $bootSumsName

$results = @(
    Assert-OfficialChecksum -FilePath $termuxApk -ChecksumPath $termuxSums
    Assert-OfficialChecksum -FilePath $bootApk -ChecksumPath $bootSums
)

$results | Format-Table -AutoSize
Write-Host "Downloaded to: $Destination"
