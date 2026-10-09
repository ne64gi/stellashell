# Offline policy checks: never installs a hook, invokes ADB, or starts scrcpy.
param([string]$Launcher = (Join-Path $PSScriptRoot '../start-stellashell.ps1'))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $Launcher), [ref]$tokens, [ref]$errors) | Out-Null
if ($errors.Count) { throw ($errors | Out-String) }
$source = Get-Content -LiteralPath $Launcher -Raw
$first = $source.IndexOf('using System;')
$last = $source.IndexOf("`n'@", $first)
Add-Type -TypeDefinition $source.Substring($first, $last - $first)

function Assert-Key($Policy, [uint32]$Key, [bool]$Up, [bool]$Focus, [bool]$Modifier, [bool]$Consumed, [bool]$Start, [uint32]$Chord = 0) {
    $actual = $Policy.Key($Key, $Up, $Focus, $Modifier)
    if ($actual.Consumed -ne $Consumed -or $actual.Start -ne $Start -or $actual.Chord -ne $Chord) {
        throw "key=$Key up=$Up focus=${Focus}: expected consumed=$Consumed start=$Start chord=$Chord; received $($actual | Out-String)"
    }
}
foreach ($key in @(0x5B, 0x5C, 0xA5)) {
    $policy = New-Object StellaMetaGesture($true, $true)
    Assert-Key $policy $key $false $true $false $true $false
    Assert-Key $policy $key $false $true $false $true $false # repeat cannot toggle twice
    Assert-Key $policy $key $true $true $false $true $true
    Assert-Key $policy $key $true $true $false $false $false

    $policy = New-Object StellaMetaGesture($true, $true)
    Assert-Key $policy $key $false $true $false $true $false
    Assert-Key $policy 0x20 $false $true $false $true $false 0x20
    Assert-Key $policy 0x20 $false $true $false $true $false # chord repeat suppressed
    Assert-Key $policy 0x20 $true $true $false $true $false
    Assert-Key $policy $key $true $true $false $true $false # chord never opens Start

    $policy = New-Object StellaMetaGesture($true, $true)
    Assert-Key $policy $key $false $true $false $true $false
    Assert-Key $policy 0xA0 $false $true $false $false $false # Shift keeps normal transport
    Assert-Key $policy 0xA0 $true $true $false $false $false
    Assert-Key $policy $key $true $true $false $true $false

    $policy = New-Object StellaMetaGesture($true, $true)
    Assert-Key $policy $key $false $true $true $true $false # other modifier already held
    Assert-Key $policy $key $true $true $false $true $false

    $policy = New-Object StellaMetaGesture($true, $true)
    Assert-Key $policy $key $false $false $false $false $false # other PC app keeps its keys
    Assert-Key $policy $key $false $true $false $false $false # repeat after focus is not captured
    Assert-Key $policy $key $true $true $false $false $false

    $policy = New-Object StellaMetaGesture($true, $true)
    Assert-Key $policy $key $false $true $false $true $false
    $policy.LoseFocus()
    Assert-Key $policy 0x41 $false $false $false $false $false
    Assert-Key $policy $key $true $true $false $true $false # returning still cancels gesture
}
$policy = New-Object StellaMetaGesture($true, $true)
Assert-Key $policy 0x5B $false $true $false $true $false
Assert-Key $policy 0x5C $false $true $true $true $false
Assert-Key $policy 0x5B $true $true $false $true $false
Assert-Key $policy 0x5C $true $true $false $true $false

$policy = New-Object StellaMetaGesture($true, $true)
Assert-Key $policy 0x5B $false $true $false $true $false
Assert-Key $policy 0x41 $false $true $false $true $false 0x41
Assert-Key $policy 0x41 $true $false $false $true $false # cleanup only, not a new outside gesture
Assert-Key $policy 0x5B $true $false $false $true $false

$policy = New-Object StellaMetaGesture($false, $true)
Assert-Key $policy 0xA5 $false $true $false $false $false
Assert-Key $policy 0xA5 $true $true $false $false $false
Assert-Key $policy 0x5B $false $true $false $true $false
Assert-Key $policy 0x5B $true $true $false $true $true
$policy = New-Object StellaMetaGesture($true, $false)
Assert-Key $policy 0x5B $false $true $false $false $false
Assert-Key $policy 0x5B $true $true $false $false $false
Assert-Key $policy 0xA5 $false $true $false $true $false
Assert-Key $policy 0xA5 $true $true $false $true $true

foreach ($key in @(0x5B, 0x5C, 0xA5)) {
    $policy = New-Object StellaMetaGesture($true, $true)
    Assert-Key $policy $key $false $true $false $true $false
    Assert-Key $policy 0xFF $false $true $false $true $false 0xFF
    Assert-Key $policy 0xFF $true $true $false $true $false
    Assert-Key $policy $key $true $true $false $true $false
}
if ([StellaMetaRelay]::AndroidKey(0x20) -ne 62 -or [StellaMetaRelay]::AndroidKey(0x41) -ne 29 -or [StellaMetaRelay]::AndroidKey(0xFF) -ne -1) { throw 'Android chord mapping changed.' }
Write-Host 'PASS launcher syntax, embedded C# compile, standalone/repeat/chords/modifiers/focus/option isolation and Android chord mapping (no native hooks or device access).'
