# Builds Facet for Windows: the browser, its uninstaller, the update zip and the paper installer.
#   pwsh windows/build.ps1 -Version 2.0            (CI runs exactly this)
# Output in dist/:
#   Facet-v<version>-Setup.exe          what people download and run
#   Facet-v<version>-windows-x64.zip    the same files unpacked: used by Facet's updater for portable copies
param(
    [string]$Version = "1.0",
    [string]$Dotnet = "dotnet",
    [string]$Out = "dist"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$work = Join-Path $root "build-work"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force $work, $Out | Out-Null
$Out = (Resolve-Path $Out).Path

function Step($name) { Write-Host "::group::$name"; Write-Host "== $name" }
function Done { Write-Host "::endgroup::" }
function Check($what) { if ($LASTEXITCODE -ne 0) { throw "$what failed (exit code $LASTEXITCODE)" } }

Step "Browser"
& $Dotnet publish windows/Facet.Windows.csproj -c Release -r win-x64 --self-contained "-p:Version=$Version" -o "$work/app"
Check "Browser build"
Done

Step "Uninstaller"
# the setup program built without a payload is the uninstaller that sits next to Facet.exe
& $Dotnet publish installer/Facet.Installer.csproj -c Release "-p:Version=$Version" "-p:IntermediateOutputPath=obj\uninstaller\" -o "$work/uninstaller"
Check "Uninstaller build"
Copy-Item "$work/uninstaller/Facet-Setup.exe" "$work/app/uninstall.exe"
Done

Step "Update zip"
$zip = Join-Path $Out "Facet-v$Version-windows-x64.zip"
if (Test-Path $zip) { Remove-Item $zip }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory("$work/app", $zip, [IO.Compression.CompressionLevel]::Optimal, $false)
Done

Step "Installer"
& $Dotnet publish installer/Facet.Installer.csproj -c Release "-p:Version=$Version" "-p:Payload=$zip" "-p:IntermediateOutputPath=obj\setup\" -o "$work/setup"
Check "Installer build"
Copy-Item "$work/setup/Facet-Setup.exe" (Join-Path $Out "Facet-v$Version-Setup.exe") -Force
Done

Get-ChildItem $Out | ForEach-Object { "{0,-36} {1,8:N1} MB" -f $_.Name, ($_.Length / 1MB) }
