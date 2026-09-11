param(
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [ValidateSet('arm64-v8a', 'x86_64')]
    [string]$Abi = 'arm64-v8a'
)

$ErrorActionPreference = 'Stop'
$strikeRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$version = '1.102.4'
$sourceHash = '784b023e825e1cca7b146ac6a7aff08b179d60d10839b51019f315dab426c871'
$cache = Join-Path $strikeRoot 'app\build\tailscale'
$source = Join-Path $cache "tailscale-$version"
$archive = Join-Path $cache "v$version.tar.gz"
$goArch = if ($Abi -eq 'x86_64') { 'amd64' } else { 'arm64' }
$compilerTarget = if ($Abi -eq 'x86_64') { 'x86_64' } else { 'aarch64' }
$compiler = Join-Path $AndroidSdk "ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\$compilerTarget-linux-android28-clang.cmd"
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Install Android NDK 27.0.12077973 first' }
New-Item -ItemType Directory -Path $cache -Force | Out-Null
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -UseBasicParsing -Uri "https://codeload.github.com/tailscale/tailscale/tar.gz/refs/tags/v$version" -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sourceHash) {
    throw 'Tailscale source checksum does not match'
}
if (-not (Test-Path -LiteralPath (Join-Path $source 'go.mod'))) {
    tar -xzf $archive -C $cache
    if ($LASTEXITCODE -ne 0) { throw 'Could not extract tailscale' }
}
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
    # Go reports toolchain and module downloads on stderr; gate on exit codes instead.
    $ErrorActionPreference = 'Continue'
    # ts_include_cli puts the daemon and the CLI in one binary; TS_BE_CLI picks the CLI at run time.
    # netstack and serve must stay: userspace networking carries the dashboard to the tailnet.
    $tags = 'ts_include_cli,ts_omit_ssh,ts_omit_systray,ts_omit_webclient,ts_omit_drive,' +
        'ts_omit_taildrop,ts_omit_kube,ts_omit_aws,ts_omit_tap,ts_omit_bird,ts_omit_synology,' +
        'ts_omit_capture,ts_omit_qrcodes,ts_omit_completion,ts_omit_flashappliance,' +
        'ts_omit_relayserver,ts_omit_tpm,ts_omit_acme,ts_omit_appconnectors,ts_omit_cloud,ts_omit_doctor'
    go build -tags $tags -trimpath -ldflags "-s -w -X tailscale.com/version.longStamp=$version -X tailscale.com/version.shortStamp=$version" -o "..\libtailscale-$Abi.so" ./cmd/tailscaled
    if ($LASTEXITCODE -ne 0) { throw 'Tailscale build failed' }
    $sourceSet = if ($Abi -eq 'x86_64') { 'debug' } else { 'main' }
    $destination = Join-Path $strikeRoot "app\src\$sourceSet\jniLibs\$Abi"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $cache "libtailscale-$Abi.so") -Destination (Join-Path $destination 'libtailscale.so')
    $notices = [Text.StringBuilder]::new()
    [void]$notices.AppendLine("tailscale $version - https://github.com/tailscale/tailscale/tree/v$version")
    [void]$notices.AppendLine('Copyright Tailscale Inc & contributors. Licensed under BSD-3-Clause.')
    [void]$notices.AppendLine('Build targets: Android arm64 (car) and x86_64 (emulator), using Go 1.26.6 and Android NDK 27.0.12077973.')
    [void]$notices.AppendLine('Built from unmodified upstream source with the ts_include_cli build tag.')
    [void]$notices.AppendLine('Build: tools/build-tailscale.ps1. Dependency notices follow.')
    $modules = @(go list -tags $tags -deps -f '{{if .Module}}{{.Module.Path}}|{{.Module.Dir}}{{end}}' ./cmd/tailscaled) |
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
    [IO.File]::WriteAllText((Join-Path $strikeRoot 'app\src\main\assets\tailscale-notices.txt'), $notices.ToString(), [Text.UTF8Encoding]::new($false))
    Get-FileHash -LiteralPath (Join-Path $destination 'libtailscale.so') -Algorithm SHA256
} finally {
    Pop-Location
    foreach ($key in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process')
    }
}
