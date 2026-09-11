param(
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [ValidateSet('arm64-v8a', 'x86_64')]
    [string]$Abi = 'arm64-v8a'
)

$ErrorActionPreference = 'Stop'
$strikeRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$version = '1.1.11'
$sourceHash = 'f54d6b4f7a08a6aee98d66eb216764d1d8524b90fb421ec007fbd2038e9fc327'
$cache = Join-Path $strikeRoot 'app\build\zrok'
$source = Join-Path $cache "zrok-$version"
$archive = Join-Path $cache "v$version.tar.gz"
$goArch = if ($Abi -eq 'x86_64') { 'amd64' } else { 'arm64' }
$compilerTarget = if ($Abi -eq 'x86_64') { 'x86_64' } else { 'aarch64' }
$compiler = Join-Path $AndroidSdk "ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\$compilerTarget-linux-android28-clang.cmd"
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Install Android NDK 27.0.12077973 first' }
New-Item -ItemType Directory -Path $cache -Force | Out-Null
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -UseBasicParsing -Uri "https://codeload.github.com/openziti/zrok/tar.gz/refs/tags/v$version" -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sourceHash) {
    throw 'Zrok source checksum does not match'
}
if (-not (Test-Path -LiteralPath (Join-Path $source 'go.mod'))) {
    tar -xzf $archive -C $cache
    if ($LASTEXITCODE -ne 0) { throw 'Could not extract zrok' }
}
$buildEnv = @{
    GOOS = 'android'; GOARCH = $goArch; CGO_ENABLED = '1'; CC = $compiler
    GOCACHE = (Join-Path $cache 'cache'); GOMODCACHE = (Join-Path $cache 'modules')
    GOPATH = (Join-Path $cache 'gopath'); GOTOOLCHAIN = 'go1.24.7'
}
$previous = @{}
foreach ($key in $buildEnv.Keys) {
    $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    [Environment]::SetEnvironmentVariable($key, $buildEnv[$key], 'Process')
}
Push-Location $source
try {
    # Go reports toolchain and module downloads on stderr; gate on exit codes instead.
    $ErrorActionPreference = 'Continue'
    # no_zrok_ui drops the web console assets, which are npm-built and absent from the source tarball.
    $tags = 'no_zrok_ui'
    go build -tags $tags -trimpath -ldflags "-s -w -X github.com/openziti/zrok/build.Version=v$version" -o "..\libzrok-$Abi.so" ./cmd/zrok
    if ($LASTEXITCODE -ne 0) { throw 'Zrok build failed' }
    $sourceSet = if ($Abi -eq 'x86_64') { 'debug' } else { 'main' }
    $destination = Join-Path $strikeRoot "app\src\$sourceSet\jniLibs\$Abi"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $cache "libzrok-$Abi.so") -Destination (Join-Path $destination 'libzrok.so')
    $notices = [Text.StringBuilder]::new()
    [void]$notices.AppendLine("zrok $version - https://github.com/openziti/zrok/tree/v$version")
    [void]$notices.AppendLine('Copyright NetFoundry Inc. Licensed under Apache-2.0.')
    [void]$notices.AppendLine('Build targets: Android arm64 (car) and x86_64 (emulator), using Go 1.24.7 and Android NDK 27.0.12077973.')
    [void]$notices.AppendLine('Built from unmodified upstream source with the no_zrok_ui build tag.')
    [void]$notices.AppendLine('Build: tools/build-zrok.ps1. Dependency notices follow.')
    $modules = @(go list -tags $tags -deps -f '{{if .Module}}{{.Module.Path}}|{{.Module.Dir}}{{end}}' ./cmd/zrok) |
        Where-Object { $_ } | Sort-Object -Unique
    foreach ($module in $modules) {
        $parts = $module.Split('|')
        if ($parts.Length -ne 2 -or -not $parts[1] -or -not (Test-Path -LiteralPath $parts[1])) { continue }
        $licenses = Get-ChildItem -LiteralPath $parts[1] -File | Where-Object { $_.Name -match '^(LICENSE|COPYING|NOTICE)' }
        foreach ($license in $licenses) {
            [void]$notices.AppendLine("`n=== $($parts[0]) / $($license.Name) ===`n")
            [void]$notices.AppendLine([IO.File]::ReadAllText($license.FullName))
        }
    }
    $goRoot = go env GOROOT
    [void]$notices.AppendLine("`n=== Go runtime LICENSE ===`n")
    [void]$notices.AppendLine([IO.File]::ReadAllText((Join-Path $goRoot 'LICENSE')))
    [IO.File]::WriteAllText((Join-Path $strikeRoot 'app\src\main\assets\zrok-notices.txt'), $notices.ToString(), [Text.UTF8Encoding]::new($false))
    Get-FileHash -LiteralPath (Join-Path $destination 'libzrok.so') -Algorithm SHA256
} finally {
    Pop-Location
    foreach ($key in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process')
    }
}
