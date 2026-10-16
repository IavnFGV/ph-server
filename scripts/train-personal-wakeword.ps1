[CmdletBinding()]
param(
    [ValidateSet('prepare','fit')][string]$Stage = 'prepare',
    [string]$RunName = 'personal-v1',
    [ValidateRange(0,100)][int]$HardNegatives = 0,
    [switch]$InstallDependencies
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if ($RunName -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Use a simple run name without path separators.' }
$output = Join-Path $root "training\pomogator\private\runs\$RunName"
Push-Location $root
try {
    if ($InstallDependencies) {
        & python -m pip install --target .tools/personal-training 'torch==2.8.0' --index-url https://download.pytorch.org/whl/cpu
        if ($LASTEXITCODE -ne 0) { throw 'PyTorch installation failed.' }
        & python -m pip install --target .tools/onnx-inspect 'numpy==2.2.6' 'onnx==1.19.1' 'onnxruntime==1.22.1'
        if ($LASTEXITCODE -ne 0) { throw 'ONNX inspection dependencies installation failed.' }
    }
    & (Join-Path $PSScriptRoot 'prepare-wakeword-dependencies.ps1')
    # Process-local paths, no global Python/environment changes.
    $runner = "import sys,runpy; sys.path[:0]=[r'.tools/personal-training',r'.tools/onnx-inspect']; sys.argv=['train_personal.py','$Stage','--output',r'$output','--hard-negatives','$HardNegatives']; runpy.run_path('training/pomogator/train_personal.py',run_name='__main__')"
    $log = Join-Path $root "logs\windows\personal-training-$RunName-$Stage.log"
    & python -S -u -c $runner 2>&1 | Tee-Object -FilePath $log
    if ($LASTEXITCODE -ne 0) { throw "Personal $Stage failed; see $log" }
} finally { Pop-Location }
