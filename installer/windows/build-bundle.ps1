# Builds Convx's setup .exe: the WiX 5 bundle in bundle.wxs around the MSI jpackage made.
param(
    [Parameter(Mandatory)] [string] $Msi,
    [Parameter(Mandatory)] [string] $Version,
    [Parameter(Mandatory)] [string] $Icon,
    [Parameter(Mandatory)] [string] $Out
)
$ErrorActionPreference = 'Stop'
$wixVersion = '5.0.2'

function Native([string] $what) {
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit $LASTEXITCODE)" }
}

if (-not (Get-Command wix -ErrorAction SilentlyContinue)) {
    dotnet tool install --global wix --version $wixVersion
    Native 'Installing WiX'
    $env:PATH = "$env:PATH;$env:USERPROFILE\.dotnet\tools"
}
wix extension add -g "WixToolset.Bal.wixext/$wixVersion"
Native 'Adding the WiX Bal extension'
wix extension add -g "WixToolset.Util.wixext/$wixVersion"
Native 'Adding the WiX Util extension'

$msiPath = (Resolve-Path $Msi).Path
$iconPath = (Resolve-Path $Icon).Path
New-Item -ItemType Directory -Force (Split-Path -Parent $Out) | Out-Null
wix build "$PSScriptRoot/bundle.wxs" -ext WixToolset.Bal.wixext -ext WixToolset.Util.wixext `
    -d "Version=$Version" -d "Msi=$msiPath" -d "Icon=$iconPath" -o $Out
Native 'Building the setup'
Write-Host "Built $Out ($((Get-Item $Out).Length) bytes), version $Version"
