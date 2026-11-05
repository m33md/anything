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
# The extensions straight from nuget.org: "wix extension add" left nothing behind on CI.
$extensions = Join-Path ([IO.Path]::GetTempPath()) "wix-extensions-$wixVersion"
$ext = foreach ($name in 'WixToolset.Bal.wixext', 'WixToolset.Util.wixext') {
    $dir = Join-Path $extensions $name
    $dll = Join-Path $dir "wixext5\$name.dll"
    if (-not (Test-Path $dll)) {
        $package = Join-Path $extensions "$name.zip"
        New-Item -ItemType Directory -Force $extensions | Out-Null
        Invoke-WebRequest "https://www.nuget.org/api/v2/package/$name/$wixVersion" -OutFile $package
        Expand-Archive $package -DestinationPath $dir -Force
    }
    if (-not (Test-Path $dll)) { throw "No $dll in the $name package" }
    '-ext'; $dll
}

$msiPath = (Resolve-Path $Msi).Path
$iconPath = (Resolve-Path $Icon).Path
New-Item -ItemType Directory -Force (Split-Path -Parent $Out) | Out-Null
wix build "$PSScriptRoot/bundle.wxs" @ext -d "Version=$Version" -d "Msi=$msiPath" -d "Icon=$iconPath" -o $Out
Native 'Building the setup'
Write-Host "Built $Out ($((Get-Item $Out).Length) bytes), version $Version"
