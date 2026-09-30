# SPDX-License-Identifier: GPL-3.0-or-later
# Requires Windows PowerShell 5.1 or PowerShell 7.
[CmdletBinding()]
param([string]$Device)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

try {
    $configPath = Join-Path $PSScriptRoot 'stellashell.json'
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        throw "Missing $configPath. Copy stellashell.example.json to stellashell.json and edit the addresses."
    }
    $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $scrcpy = Join-Path $PSScriptRoot 'scrcpy.exe'
    $adb = Join-Path $PSScriptRoot 'adb.exe'
    foreach ($binary in @($scrcpy, $adb)) {
        if (-not (Test-Path -LiteralPath $binary -PathType Leaf)) {
            throw "Missing $binary. Use the complete official scrcpy Windows distribution, including its DLLs and server."
        }
    }
    if ([string]::IsNullOrWhiteSpace($Device)) { $Device = $config.default }
    if ([string]::IsNullOrWhiteSpace($Device)) { throw 'Specify -Device or config.default.' }
    $selected = $config.devices.PSObject.Properties[$Device]
    if ($null -eq $selected) {
        $available = $config.devices.PSObject.Properties.Name -join ', '
        throw "Unknown device '$Device'. Available: $available"
    }
    $profile = $selected.Value
    $address = [string]$profile.address
    $resolution = [string]$profile.resolution
    $dpi = 0
    if ($address -notmatch '^(?:[A-Za-z0-9][A-Za-z0-9.-]*|\[[0-9a-fA-F:]+\]):([0-9]{1,5})$') {
        throw 'address must be host:port (IPv6: [address]:port).'
    }
    if ([int]$Matches[1] -lt 1 -or [int]$Matches[1] -gt 65535) { throw 'Invalid ADB port.' }
    if ($resolution -notmatch '^[1-9][0-9]*x[1-9][0-9]*$') { throw 'resolution must be WIDTHxHEIGHT.' }
    if (-not [int]::TryParse([string]$profile.dpi, [ref]$dpi) -or $dpi -le 0) { throw 'dpi must be a positive integer.' }
    $keyboard = [string]$config.scrcpy.keyboard
    $imePolicy = [string]$config.scrcpy.imePolicy
    $package = [string]$config.stella.package
    if ($keyboard -notin @('uhid', 'sdk')) { throw 'keyboard must be uhid or sdk for this TCP session.' }
    if ($imePolicy -notin @('local', 'fallback', 'hide')) { throw 'imePolicy must be local, fallback or hide.' }
    if ($package -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$') { throw 'Invalid Android package name.' }
    if ($config.scrcpy.systemDecorations -isnot [bool] -or $config.scrcpy.fullscreen -isnot [bool]) {
        throw 'systemDecorations and fullscreen must be JSON booleans (not strings).'
    }

    Write-Host "`n=== StellaShell session ==="
    Write-Host "Device: $Device / $address"
    Write-Host "Display: $resolution / $dpi dpi`n"
    # PowerShell 5.1 may turn native stderr into terminating errors. Capture it
    # with Continue, then explicitly check native exit codes on both PS versions.
    $ErrorActionPreference = 'Continue'
    $connectOutput = & $adb connect $address 2>&1
    $connectExit = $LASTEXITCODE
    $stateOutput = & $adb -s $address get-state 2>&1
    $stateExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $connectOutput | Out-Host
    if ($connectExit -ne 0 -or $stateExit -ne 0 -or (($stateOutput -join "`n").Trim() -ne 'device')) {
        throw "ADB is not ready: $address. Check network, TCP ADB and the phone's authorization prompt. $stateOutput"
    }
    $scrcpyArgs = @('-s', $address, "--keyboard=$keyboard",
        "--new-display=$resolution/$dpi", "--no-vd-destroy-content", "--display-ime-policy=$imePolicy", "--start-app=$package")
    if (-not $config.scrcpy.systemDecorations) { $scrcpyArgs += '--no-vd-system-decorations' }
    if ($config.scrcpy.fullscreen) { $scrcpyArgs += '--fullscreen' }
    Write-Host 'Starting StellaShell (close scrcpy to end this virtual-display session)...'
    & $scrcpy @scrcpyArgs
    $sessionExit = $LASTEXITCODE
    if ($sessionExit -ne 0) { throw "scrcpy exited with code $sessionExit. See its output above." }
    exit 0
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
