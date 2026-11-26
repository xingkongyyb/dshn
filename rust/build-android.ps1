[CmdletBinding()]
param(
    [string]$NdkHome = "",
    [string]$CargoHome = $env:CARGO_HOME,
    [string]$RustupHome = $env:RUSTUP_HOME,
    [string]$TargetDir = ""
)

$ErrorActionPreference = 'Stop'

# Always prefer an ASCII NDK path on Windows because LLVM clang.exe fails with 0xc0000142 under non-ASCII paths.
if ([string]::IsNullOrWhiteSpace($NdkHome)) {
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_NDK_HOME) -and ($env:ANDROID_NDK_HOME -notmatch '[^\x00-\x7F]')) {
        $NdkHome = $env:ANDROID_NDK_HOME
    } else {
        $NdkHome = 'C:\gemini\.android-tools\ndk\27.2.12479018'
    }
}
if ([string]::IsNullOrWhiteSpace($CargoHome)) {
    $CargoHome = 'C:\gemini\.cargo'
}
if ([string]::IsNullOrWhiteSpace($RustupHome)) {
    $RustupHome = 'C:\gemini\.rustup'
}

$env:CARGO_HOME = $CargoHome
$env:RUSTUP_HOME = $RustupHome
$env:PATH = "$CargoHome\bin;$env:PATH"

$llvmPrebuilt = Join-Path $NdkHome 'toolchains\llvm\prebuilt\windows-x86_64'
$llvmBin = Join-Path $llvmPrebuilt 'bin'
$sysroot = Join-Path $llvmPrebuilt 'sysroot'
$androidLinker = Join-Path $llvmBin 'aarch64-linux-android30-clang.cmd'
$clangExe = Join-Path $llvmBin 'clang.exe'
$lldExe = Join-Path $llvmBin 'ld.lld.exe'
$androidAr = Join-Path $llvmBin 'llvm-ar.exe'
if (-not (Test-Path $clangExe)) {
    throw "Android NDK clang.exe not found: $clangExe"
}

$rustRoot = $PSScriptRoot
$repoRoot = Split-Path -Parent $rustRoot
$jniOut = Join-Path $repoRoot 'app\src\main\jniLibs\arm64-v8a'
New-Item -ItemType Directory -Force -Path $jniOut | Out-Null

# Keep Cargo target directory on an ASCII path on Windows to avoid linker path-encoding issues.
if ([string]::IsNullOrWhiteSpace($TargetDir)) {
    $TargetDir = 'C:\gemini\.gradle-tmp\dshn-rust-target'
}
New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
$env:CARGO_TARGET_DIR = $TargetDir

Push-Location $rustRoot
try {
    Write-Output "=== 1. Running host Rust unit tests (dshn-core, pure Rust) ==="
    & cargo test -p dshn-core --lib --no-default-features
    if ($LASTEXITCODE -ne 0) { throw "Host Rust unit tests failed ($LASTEXITCODE)" }

    Write-Output "=== 2. Building dshn-core (aarch64-linux-android, 16KB page aligned) ==="
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $androidLinker
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_AR = $androidAr
    $env:CC_aarch64_linux_android = $clangExe
    $env:AR_aarch64_linux_android = $androidAr
    $env:CFLAGS_aarch64_linux_android = "--target=aarch64-linux-android30 --sysroot=$sysroot"
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS = '-C link-arg=-Wl,-z,max-page-size=16384'
    & cargo build --release -p dshn-core --target aarch64-linux-android
    if ($LASTEXITCODE -ne 0) { throw "dshn-core build failed ($LASTEXITCODE)" }

    Write-Output "=== 3. Building dshn-mcp-relay (static aarch64-unknown-linux-musl, 16KB aligned) ==="
    $env:CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER = $lldExe
    $env:CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_RUSTFLAGS = '-C linker-flavor=ld.lld -C target-feature=+crt-static -C link-arg=-z -C link-arg=max-page-size=16384'
    & cargo build --release -p dshn-mcp-relay --target aarch64-unknown-linux-musl
    if ($LASTEXITCODE -ne 0) { throw "dshn-mcp-relay build failed ($LASTEXITCODE)" }

    $coreSo = Join-Path $TargetDir 'aarch64-linux-android\release\libdshn_core.so'
    $relayBin = Join-Path $TargetDir 'aarch64-unknown-linux-musl\release\dshn-mcp-relay'
    Copy-Item -Force $coreSo (Join-Path $jniOut 'libdshn_core.so')
    Copy-Item -Force $relayBin (Join-Path $jniOut 'libdshn_mcp_relay.so')

    Write-Output "=== 4. Verifying ELF architecture & 16KB alignment ==="
    $readelf = Join-Path $llvmBin 'llvm-readelf.exe'
    & $readelf -h -l (Join-Path $jniOut 'libdshn_core.so') | Select-String -Pattern "Machine:|LOAD|Class:"
    & $readelf -h -l (Join-Path $jniOut 'libdshn_mcp_relay.so') | Select-String -Pattern "Machine:|LOAD|Class:"

    Write-Output "=== RUST ARTIFACTS READY ==="
    Get-Item (Join-Path $jniOut 'libdshn_core.so'), (Join-Path $jniOut 'libdshn_mcp_relay.so') | Select-Object Name, Length
} finally {
    Pop-Location
}
