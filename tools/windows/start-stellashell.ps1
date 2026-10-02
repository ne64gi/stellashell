# SPDX-License-Identifier: GPL-3.0-or-later
# Requires Windows PowerShell 5.1 or PowerShell 7.
[CmdletBinding()]
param([string]$Device, [switch]$Setup, [switch]$NonInteractive)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

function Test-StellaEndpoint([string]$Value) {
    if ($Value -notmatch '^(?:[A-Za-z0-9][A-Za-z0-9.-]*|\[[0-9a-fA-F:]+\]):([0-9]{1,5})$') { return $false }
    return ([int]$Matches[1] -ge 1 -and [int]$Matches[1] -le 65535)
}
function Read-StellaEndpoint([string]$Label) {
    while ($true) {
        $value = (Read-Host "$Label (IP:port, q = cancel)").Trim()
        if ($value -eq 'q') { throw 'Connection cancelled.' }
        if (Test-StellaEndpoint $value) { return $value }
        Write-Host 'Enter an IP/hostname and port 1-65535. IPv6: [address]:port.'
    }
}
function Invoke-StellaAdb([string[]]$Arguments) {
    # Only fixed command words and validated endpoints are passed here.
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $adb
    $info.Arguments = $Arguments -join ' '
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($info)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(12000)) {
            $process.Kill()
            return @{ Code = 124; Text = 'ADB timed out. Check Wi-Fi / VPN and the current port.' }
        }
        return @{ Code = $process.ExitCode; Text = ($stdout.Result + $stderr.Result).Trim() }
    } finally { $process.Dispose() }
}
function Connect-StellaEndpoint([string]$Endpoint) {
    if (-not (Test-StellaEndpoint $Endpoint)) { return $false }
    $result = Invoke-StellaAdb -Arguments @('connect', $Endpoint)
    Write-Host $result.Text
    if ($result.Code -ne 0) { return $false }
    $state = Invoke-StellaAdb -Arguments @('-s', $Endpoint, 'get-state')
    if ($state.Code -eq 0 -and $state.Text -eq 'device') { return $true }
    Write-Host "Not ready: $($state.Text). Check the phone's authorization prompt."
    return $false
}
function Resolve-StellaConnection([string]$Initial, [bool]$ForceSetup, [bool]$Batch) {
    if (-not $ForceSetup -and (Connect-StellaEndpoint $Initial)) { return $Initial }
    if ($Batch) { throw 'ADB unavailable. Run without -NonInteractive to pair/connect.' }
    while ($true) {
        Write-Host "`n=== Android wireless debugging ==="
        Write-Host 'On Android 11+: Settings > Developer options > Wireless debugging > ON.'
        Write-Host 'Use the same Wi-Fi, or a network/VPN that can reach the phone.'
        Write-Host '1  Pair this PC (first time / pairing was removed)'
        Write-Host '2  Already paired / TCP ADB ready: enter connection IP:port'
        Write-Host 'q  Cancel'
        $choice = Read-Host 'Choose 1 / 2 / q'
        if ($choice -eq 'q') { throw 'Connection cancelled.' }
        if ($choice -notin @('1','2')) { continue }
        if ($choice -eq '1') {
            Write-Host 'On the phone: Pair device with pairing code. Keep that dialog open.'
            $pairAddress = Read-StellaEndpoint 'PAIRING address shown in that dialog'
            Write-Host 'Enter the six-digit code when adb asks. It is not saved to configuration.'
            $ErrorActionPreference = 'Continue'
            & $adb pair $pairAddress | Out-Host
            $pairExit = $LASTEXITCODE
            $ErrorActionPreference = 'Stop'
            if ($pairExit -ne 0) { Write-Host 'Pairing failed. Open a fresh pairing dialog and retry.'; continue }
        }
        Write-Host 'Return to the main Wireless debugging screen: use its IP address & port.'
        Write-Host 'The CONNECTION port is different from the PAIRING port. No :5555 is required.'
        $candidate = Read-StellaEndpoint 'CONNECTION address (or your existing TCP :5555 endpoint)'
        if (Connect-StellaEndpoint $candidate) { return $candidate }
    }
}

$previousIconPath = [Environment]::GetEnvironmentVariable('SCRCPY_ICON_PATH', 'Process')

try {
    $configPath = Join-Path $PSScriptRoot 'stellashell.json'
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        if ($NonInteractive) { throw "Missing $configPath." }
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'stellashell.example.json') -Destination $configPath
        Write-Host 'Created stellashell.json from the sample. Connection setup will follow.'
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

    $rightAltAsMeta = $true
    $mappingOption = $config.scrcpy.PSObject.Properties['rightAltAsMeta']
    if ($null -ne $mappingOption) {
        if ($mappingOption.Value -isnot [bool]) { throw 'rightAltAsMeta must be a JSON boolean.' }
        $rightAltAsMeta = $mappingOption.Value
    }
    if ($rightAltAsMeta -and [Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw 'Right-Alt remapping requires Windows. Set scrcpy.rightAltAsMeta to false to disable it.'
    }

    Write-Host "`n=== StellaShell session ==="
    Write-Host "Device: $Device / $address"
    Write-Host "Display: $resolution / $dpi dpi`n"
    $address = Resolve-StellaConnection $address ([bool]$Setup) ([bool]$NonInteractive)
    if ($address -ne [string]$profile.address -and -not $NonInteractive) {
        if ((Read-Host 'Save this connection address in stellashell.json? [y/N]') -eq 'y') {
            $profile.address = $address
            $config | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $configPath -Encoding UTF8
        }
    }
    Write-Host "Connected: $address"
    $scrcpyArgs = @('-s', $address, "--keyboard=$keyboard",
        "--new-display=$resolution/$dpi", "--no-vd-destroy-content", "--display-ime-policy=$imePolicy", "--start-app=$package")
    if (-not $config.scrcpy.systemDecorations) { $scrcpyArgs += '--no-vd-system-decorations' }
    if ($config.scrcpy.fullscreen) { $scrcpyArgs += '--fullscreen' }
    if ($rightAltAsMeta) {
        # Meta must reach Android, not scrcpy's own MOD shortcuts (including paste).
        $scrcpyArgs += '--shortcut-mod=lalt'
        $ErrorActionPreference = 'Continue'
        $inputHelp = & $adb -s $address shell input help 2>&1
        $ErrorActionPreference = 'Stop'
        if (($inputHelp -join "`n") -notmatch 'keycombination') {
            throw 'This Android input command lacks keycombination. Set rightAltAsMeta=false to use the original launcher.'
        }
        if (-not ('StellaMetaRelay' -as [type])) {
            Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;

// Original StellaShell helper. ADB receives complete Meta chords, never Windows SendInput.
public sealed class StellaMetaRelay : IDisposable {
    [StructLayout(LayoutKind.Sequential)] struct Key { public uint vk, scan, flags, time; public UIntPtr extra; }
    [StructLayout(LayoutKind.Sequential)] struct Point { public int x, y; }
    [StructLayout(LayoutKind.Sequential)] struct Message { public IntPtr hwnd; public uint message; public UIntPtr wParam; public IntPtr lParam; public uint time; public Point pt; public uint privateValue; }
    delegate IntPtr Hook(int code, IntPtr message, IntPtr data);
    [DllImport("user32.dll", SetLastError=true)] static extern IntPtr SetWindowsHookEx(int type, Hook callback, IntPtr module, uint thread);
    [DllImport("user32.dll")] static extern bool UnhookWindowsHookEx(IntPtr hook);
    [DllImport("user32.dll")] static extern IntPtr CallNextHookEx(IntPtr hook, int code, IntPtr message, IntPtr data);
    [DllImport("user32.dll")] static extern bool PeekMessage(out Message message, IntPtr hwnd, uint min, uint max, uint remove);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll")] static extern short GetAsyncKeyState(int key);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode)] static extern IntPtr GetModuleHandle(string name);
    readonly int pid; readonly string adb, serial, package;
    readonly ManualResetEvent stopped=new ManualResetEvent(false), ready=new ManualResetEvent(false);
    readonly BlockingCollection<string> queue=new BlockingCollection<string>(16);
    readonly HashSet<uint> consumed=new HashSet<uint>();
    Thread inputThread, worker; Hook callback; IntPtr handle;
    Exception startError; bool altCaptured, chordUsed, gestureActive;
    volatile bool overflow, unsupported;
    public StellaMetaRelay(int processId, string adbPath, string address, string appPackage) { pid=processId; adb=adbPath; serial=address; package=appPackage; }
    bool Focused() { uint current; GetWindowThreadProcessId(GetForegroundWindow(),out current); return current==(uint)pid; }
    public static int AndroidKey(uint key) {
        if(key>=0x41&&key<=0x5A)return (int)key-0x41+29;
        if(key>=0x30&&key<=0x39)return (int)key-0x30+7;
        if(key>=0x70&&key<=0x7B)return (int)key-0x70+131;
        if(key>=0x60&&key<=0x69)return (int)key-0x60+144;
        switch(key) {
            case 0x20:return 62; case 0x09:return 61; case 0x0D:return 66; case 0x1B:return 111;
            case 0x08:return 67; case 0x2E:return 112; case 0x2D:return 124;
            case 0x25:return 21; case 0x26:return 19; case 0x27:return 22; case 0x28:return 20;
            case 0x24:return 122; case 0x23:return 123; case 0x21:return 92; case 0x22:return 93;
            case 0xBD:return 69; case 0xBB:return 70; case 0xDB:return 71; case 0xDD:return 72;
            case 0xDC:return 73; case 0xBA:return 74; case 0xDE:return 75; case 0xBC:return 55;
            case 0xBE:return 56; case 0xBF:return 76; case 0xC0:return 68;
            default:return -1;
        }
    }
    void Enqueue(string keys) { if(!queue.TryAdd(keys))overflow=true; }
    IntPtr OnKey(int code,IntPtr message,IntPtr data) {
        if(code<0)return CallNextHookEx(handle,code,message,data);
        Key key=(Key)Marshal.PtrToStructure(data,typeof(Key));
        if((key.flags&0x10)!=0)return CallNextHookEx(handle,code,message,data); // ignore injected events
        bool up=(key.flags&0x80)!=0;
        if(key.vk==0xA5) { // right Alt only; left Alt remains scrcpy's normal shortcut modifier
            if(up&&altCaptured) {
                if(gestureActive&&!chordUsed&&Focused())Enqueue("stella-start");
                altCaptured=false;gestureActive=false;return new IntPtr(1);
            }
            if(!up&&(altCaptured||Focused())) {
                if(!altCaptured){altCaptured=true;gestureActive=true;chordUsed=false;}
                return new IntPtr(1);
            }
        }
        if(up&&consumed.Remove(key.vk))return new IntPtr(1);
        if(!up&&consumed.Contains(key.vk))return new IntPtr(1); // one chord per press, not autorepeat
        bool modifier=key.vk>=0xA0&&key.vk<=0xA4 || key.vk==0x5B || key.vk==0x5C;
        if(!up&&altCaptured&&gestureActive&&Focused()&&!modifier) {
            chordUsed=true;consumed.Add(key.vk);int android=AndroidKey(key.vk);
            if(android<0){unsupported=true;return new IntPtr(1);}
            string keys="keycombination 117";
            if((GetAsyncKeyState(0x10)&0x8000)!=0)keys+=" 59";
            if((GetAsyncKeyState(0x11)&0x8000)!=0)keys+=" 113";
            if((GetAsyncKeyState(0xA4)&0x8000)!=0)keys+=" 57";
            Enqueue(keys+" "+android);return new IntPtr(1);
        }
        return CallNextHookEx(handle,code,message,data);
    }
    public void Start() {
        inputThread=new Thread(InputLoop);inputThread.IsBackground=true;inputThread.Start();
        if(!ready.WaitOne(5000))throw new InvalidOperationException("Keyboard hook startup timed out.");
        if(startError!=null)throw new InvalidOperationException("Cannot install right-Alt remapping.",startError);
        worker=new Thread(AdbLoop);worker.IsBackground=true;worker.Start();
    }
    void InputLoop() {
        try {
            callback=OnKey;handle=SetWindowsHookEx(13,callback,GetModuleHandle(null),0);
            if(handle==IntPtr.Zero)throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            ready.Set();
            while(!stopped.WaitOne(10)) {
                Message msg;while(PeekMessage(out msg,IntPtr.Zero,0,0,1)){}
                if(!Focused()){gestureActive=false;chordUsed=true;}
            }
        }catch(Exception e){startError=e;ready.Set();}
        finally{if(handle!=IntPtr.Zero)UnhookWindowsHookEx(handle);}
    }
    void AdbLoop() {
        while(!stopped.WaitOne(0)) {
            if(overflow){Console.Error.WriteLine("IME shortcut queue full; shortcut discarded.");overflow=false;}
            if(unsupported){Console.Error.WriteLine("This right-Alt key combination is not supported.");unsupported=false;}
            string keys;if(!queue.TryTake(out keys,50))continue;
            if(!Focused())continue; // don't send delayed shortcuts after switching applications
            try {
                string action=keys=="stella-start"?"am broadcast -n "+package+"/net.fuyumori.stellashell.StartMenuReceiver -a "+package+".TOGGLE_START":"input "+keys;
                var info=new ProcessStartInfo(adb,"-s "+serial+" shell "+action);
                info.UseShellExecute=false;info.CreateNoWindow=true;
                using(var command=Process.Start(info)) {
                    int waited=0;
                    while(!command.WaitForExit(50)&&!stopped.WaitOne(0)&&waited<3000)waited+=50;
                    if(!command.HasExited){command.Kill();Console.Error.WriteLine("Android shortcut interrupted or timed out; not retried.");}
                    else if(command.ExitCode!=0)Console.Error.WriteLine("Android rejected the shortcut. Check ADB input keycombination support.");
                }
            }catch(Exception){Console.Error.WriteLine("Could not send Android shortcut.");}
        }
    }
    public void Dispose() {
        stopped.Set();
        if(inputThread!=null)inputThread.Join(2000);
        if(worker!=null)worker.Join(4000);
        // Events remain alive if a native call has not returned; the background threads then exit safely.
    }
}
'@
        }
    }
    $iconPath = Join-Path $PSScriptRoot 'stellashell.png'
    if (Test-Path -LiteralPath $iconPath -PathType Leaf) {
        [Environment]::SetEnvironmentVariable('SCRCPY_ICON_PATH', $iconPath, 'Process')
    } else {
        Write-Warning 'stellashell.png is missing; using the existing scrcpy icon.'
    }
    Write-Host 'Starting StellaShell (close scrcpy to end this virtual-display session)...'
    if ($rightAltAsMeta) {
        $session = $null
        $relay = $null
        try {
            # All argument values were restricted above; no shell metacharacters/spaces are accepted.
            $startInfo = New-Object System.Diagnostics.ProcessStartInfo
            $startInfo.FileName = $scrcpy
            $startInfo.Arguments = $scrcpyArgs -join ' '
            $startInfo.UseShellExecute = $false
            $session = [System.Diagnostics.Process]::Start($startInfo)
            $relay = New-Object StellaMetaRelay($session.Id, $adb, $address, $package)
            $relay.Start()
            Write-Host 'Right Alt alone -> Stella Start. Right Alt + Space -> Meta + Space. Left Alt remains scrcpy MOD.'
            while (-not $session.WaitForExit(100)) { }
            $sessionExit = $session.ExitCode
        } finally {
            if ($null -ne $relay) { $relay.Dispose() }
            if ($null -ne $session) {
                if (-not $session.HasExited) { $session.Kill() }
                $session.Dispose()
            }
        }
    } else {
        & $scrcpy @scrcpyArgs
        $sessionExit = $LASTEXITCODE
    }
    if ($sessionExit -ne 0) { throw "scrcpy exited with code $sessionExit. See its output above." }
    exit 0
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
} finally {
    [Environment]::SetEnvironmentVariable('SCRCPY_ICON_PATH', $previousIconPath, 'Process')
}
