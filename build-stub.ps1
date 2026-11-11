# Builds the stub for Windows and puts it where the Maven plugin and the tests look for it (stubs/windows-x64).
# Needs Rust (https://rustup.rs). Run it before `mvn install` whenever the stub's code changed.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$cargoBin = Join-Path $env:USERPROFILE '.cargo\bin'
if (Test-Path $cargoBin) { $env:PATH = "$cargoBin;$env:PATH" }

Push-Location (Join-Path $root 'stub')
try {
    cargo build --release
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }
} finally {
    Pop-Location
}

$target = Join-Path $root 'stubs\windows-x64'
New-Item -ItemType Directory -Force $target | Out-Null
Copy-Item (Join-Path $root 'stub\target\release\jpack-stub.exe') (Join-Path $target 'jpack-stub.exe') -Force
Write-Host "stub: $(Join-Path $target 'jpack-stub.exe') ($((Get-Item (Join-Path $target 'jpack-stub.exe')).Length) bytes)"
