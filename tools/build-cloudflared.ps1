param(
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [ValidateSet('arm64-v8a', 'x86_64')]
    [string]$Abi = 'arm64-v8a'
)

$ErrorActionPreference = 'Stop'
$strikeRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$version = '2026.8.3'
$sourceHash = '04cd85af52c2c012f08212c878b4c403eadf410865f2356a80f361d475d2fc92'
$cache = Join-Path $strikeRoot 'app\build\cloudflared'
$source = Join-Path $cache "cloudflared-$version"
$archive = Join-Path $cache "$version.tar.gz"
$goArch = if ($Abi -eq 'x86_64') { 'amd64' } else { 'arm64' }
$compilerTarget = if ($Abi -eq 'x86_64') { 'x86_64' } else { 'aarch64' }
$compiler = Join-Path $AndroidSdk "ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\$compilerTarget-linux-android28-clang.cmd"
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Install Android NDK 27.0.12077973 first' }
New-Item -ItemType Directory -Path $cache -Force | Out-Null
go test -count=1 (Join-Path $PSScriptRoot 'cloudflared\dns_android.go') (Join-Path $PSScriptRoot 'cloudflared\dns_android_test.go')
if ($LASTEXITCODE -ne 0) { throw 'Cloudflared DNS tests failed' }
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -UseBasicParsing -Uri "https://codeload.github.com/cloudflare/cloudflared/tar.gz/refs/tags/$version" -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sourceHash) {
    throw 'Cloudflared source checksum does not match'
}
if (-not (Test-Path -LiteralPath (Join-Path $source 'go.mod'))) {
    tar -xzf $archive -C $cache
    if ($LASTEXITCODE -ne 0) { throw 'Could not extract cloudflared' }
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'cloudflared\parent_android.go') -Destination (Join-Path $source 'cmd\cloudflared\parent_android.go')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'cloudflared\dns_android.go') -Destination (Join-Path $source 'edgediscovery\allregions\dns_android.go')
$buildEnv = @{
    GOOS = 'android'; GOARCH = $goArch; CGO_ENABLED = '1'; CC = $compiler
    GOCACHE = (Join-Path $cache 'cache'); GOMODCACHE = (Join-Path $cache 'modules')
    GOPATH = (Join-Path $cache 'gopath'); GOTOOLCHAIN = 'go1.26.6'
}
$previous = @{}
foreach ($key in $buildEnv.Keys) {
    $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    [Environment]::SetEnvironmentVariable($key, $buildEnv[$key], 'Process')
}
Push-Location $source
try {
    go build -mod=vendor -trimpath -ldflags "-s -w -X main.Version=$version -X github.com/cloudflare/cloudflared/cmd/cloudflared/updater.BuiltForPackageManager=strike" -o "..\libcloudflared-$Abi.so" ./cmd/cloudflared
    if ($LASTEXITCODE -ne 0) { throw 'Cloudflared build failed' }
    $sourceSet = if ($Abi -eq 'x86_64') { 'debug' } else { 'main' }
    $destination = Join-Path $strikeRoot "app\src\$sourceSet\jniLibs\$Abi"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $cache "libcloudflared-$Abi.so") -Destination (Join-Path $destination 'libcloudflared.so')
    $notices = [Text.StringBuilder]::new()
    [void]$notices.AppendLine("cloudflared $version - https://github.com/cloudflare/cloudflared/tree/$version")
    [void]$notices.AppendLine('Copyright Cloudflare, Inc. Licensed under Apache-2.0.')
    [void]$notices.AppendLine('Build targets: Android arm64 (car) and x86_64 (emulator), using Go 1.26.6 and Android NDK 27.0.12077973.')
    [void]$notices.AppendLine('Strike adds tools/cloudflared/parent_android.go for process cleanup and dns_android.go for Android system DNS. Other upstream source files are unmodified.')
    [void]$notices.AppendLine('Build: tools/build-cloudflared.ps1. Vendored dependency notices follow, including components not used by this build.')
    $licenses = @(Get-Item -LiteralPath (Join-Path $source 'LICENSE')) + @(Get-ChildItem -LiteralPath (Join-Path $source 'vendor') -Recurse -File | Where-Object { $_.Name -match '^(LICENSE|COPYING|NOTICE)' } | Sort-Object FullName)
    foreach ($license in $licenses) {
        [void]$notices.AppendLine("`n=== $($license.FullName.Substring($source.Length + 1).Replace('\', '/')) ===`n")
        [void]$notices.AppendLine([IO.File]::ReadAllText($license.FullName))
    }
    $goRoot = go env GOROOT
    [void]$notices.AppendLine("`n=== Go runtime LICENSE ===`n")
    [void]$notices.AppendLine([IO.File]::ReadAllText((Join-Path $goRoot 'LICENSE')))
    [IO.File]::WriteAllText((Join-Path $strikeRoot 'app\src\main\assets\cloudflared-notices.txt'), $notices.ToString(), [Text.UTF8Encoding]::new($false))
    Get-FileHash -LiteralPath (Join-Path $destination 'libcloudflared.so') -Algorithm SHA256
} finally {
    Pop-Location
    foreach ($key in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process')
    }
}
