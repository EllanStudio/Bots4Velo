[CmdletBinding()]
param(
    [string]$NetworkRoot = (Join-Path $PSScriptRoot "..\test-network\afk-26.2"),
    [string]$ExpectedPluginVersion = "3.0.6",
    [switch]$RequireBotsInPlay
)

$ErrorActionPreference = "Stop"

function Assert-ListeningPort {
    param([int]$Port, [string]$Name)

    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if (-not $listener) {
        throw "$Name is not listening on 127.0.0.1:$Port. Start the local network first."
    }
}

$resolvedRoot = (Resolve-Path -LiteralPath $NetworkRoot).Path
$velocityDirectory = Join-Path $resolvedRoot "velocity"
$pluginDirectory = Join-Path $velocityDirectory "plugins\bots4velo"
$pluginJar = Get-ChildItem -LiteralPath (Join-Path $velocityDirectory "plugins") -Filter "bots4velo*.jar" |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1

if (-not $pluginJar) {
    throw "No Bots4Velo JAR exists in $velocityDirectory\\plugins."
}

Assert-ListeningPort -Port 25590 -Name "Velocity"
Assert-ListeningPort -Port 25591 -Name "Paper lobby"
Assert-ListeningPort -Port 25592 -Name "Paper AFK backend"

$checksum = Join-Path (Split-Path -Parent $pluginJar.FullName) ($pluginJar.Name + ".sha256")
if (Test-Path -LiteralPath $checksum) {
    $expectedHash = (Get-Content -LiteralPath $checksum -Raw).Trim().Split(" ", [System.StringSplitOptions]::RemoveEmptyEntries)[0]
    $actualHash = (Get-FileHash -LiteralPath $pluginJar.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash.ToLowerInvariant()) {
        throw "SHA-256 mismatch for $($pluginJar.Name)."
    }
}

$logs = Get-ChildItem -LiteralPath $velocityDirectory -Filter "*.out.log" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTimeUtc -Descending
$logText = ($logs | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
if ($logText -notmatch [regex]::Escape("Loaded plugin bots4velo $ExpectedPluginVersion")) {
    throw "Velocity logs do not show Bots4Velo $ExpectedPluginVersion loading."
}

if ($RequireBotsInPlay -and $logText -notmatch "entered PLAY") {
    throw "No bot has reached PLAY according to the Velocity logs."
}

[pscustomobject]@{
    Proxy = "127.0.0.1:25590"
    Backends = "127.0.0.1:25591, 127.0.0.1:25592"
    Plugin = $pluginJar.Name
    Checksum = if (Test-Path -LiteralPath $checksum) { "verified" } else { "not present (build output only)" }
    BotsInPlayRequired = [bool]$RequireBotsInPlay
} | Format-List
