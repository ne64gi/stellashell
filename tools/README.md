# Optional desktop/session tools

- [Windows session launcher](windows/README.md): scrcpy virtual display, selectable device profiles.
- [Linux / macOS launcher](unix/README.md): interactive wireless pairing/connection, no fixed port required.
- Termux self-ADB helpers below are separate, optional setup tools.

# Optional Termux self-ADB helpers

These tools are **not required by StellaShell**. Normal setup is Shizuku → StellaShell.
They do not start Shizuku or grant StellaShell any permission.

- `setup-self-adb.sh`: initial Termux package update/upgrade, `android-tools` / `nmap` installation, interactive pairing, then TCP 5555. Pairing port and connection port are different.
- `adb5555 [connection-port]`: daily recovery; reconnects to 5555 first, otherwise uses an already paired wireless-debugging connection to run `adb tcpip 5555`. Checks actual device state before reporting success. No package upgrades or pairing prompts.

Run from Termux:

```bash
bash setup-self-adb.sh
mkdir -p ~/bin
cp adb5555 ~/bin/adb5555
chmod +x ~/bin/adb5555
~/bin/adb5555
# When auto-discovery misses the port shown in Android's Wireless debugging screen:
~/bin/adb5555 54321
```

Automatic discovery only scans **localhost ports 30000–50000**. Android may choose a port outside this range; supply the connection port explicitly. Wireless debugging must already be enabled, with this Termux ADB key paired/authorized. These scripts cannot enable it or bypass pairing.

TCP 5555 is not reboot-persistent and may listen beyond localhost, depending on the device. Use a trusted network; `adb -s 127.0.0.1:5555 usb` returns adbd to USB mode and disconnects TCP clients.

For optional Tasker automation, use a Wi-Fi-connected profile, wait about 60 seconds, then run the daily helper through Termux:Tasker. Place a wrapper in `~/.termux/tasker/` that executes `"$HOME/bin/adb5555"`, following the plugin's setup instructions. Do not run initial setup on every connection. Shizuku startup remains a separate step; TCP 5555 readiness does not imply Shizuku is running.

Host-side checks cover shell syntax and mocked success/failure paths; the bundled scripts have not been re-run on these phones during this release.
