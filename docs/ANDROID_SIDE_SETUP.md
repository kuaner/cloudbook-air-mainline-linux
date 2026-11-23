# eCloudBook Air Android Side Setup

ADB and switcher last verified: 2026-11-23

## Device

- Model: `eCloudBook Air 24RTW`
- Device: `rk3568_r_w600d2`
- Android: 11
- Serial: `291005950753`
- Network ADB: TCP port `5555`. Replace the IP in the examples with the device's current address.
- Root: available via `su 0`

## ADB persistence

The vendor init script `/vendor/etc/init/hw/init.rk30board.rc` uses these
properties:

| Property | Value | Effect |
| --- | --- | --- |
| `persist.sys.adb_enable` | `1` | start/restart `adbd` |
| `persist.internet_adb_enable` | `1` | set `service.adb.tcp.port=5555` and restart `adbd` |
| `persist.vendor.adbmode.enable` | `1` | vendor switch that prevents the boot script from stopping `adbd` |

Applied values:

```bash
adb -s 192.168.123.128:5555 shell su 0 setprop persist.sys.adb_enable 1
adb -s 192.168.123.128:5555 shell su 0 setprop persist.internet_adb_enable 1
adb -s 192.168.123.128:5555 shell su 0 setprop persist.vendor.adbmode.enable 1
adb -s 192.168.123.128:5555 shell su 0 setprop persist.sys.usb.config adb
adb -s 192.168.123.128:5555 shell settings put global adb_enabled 1
adb -s 192.168.123.128:5555 shell settings put global development_settings_enabled 1
adb -s 192.168.123.128:5555 shell settings put global adb_wifi_enabled 1
```

Runtime result before reboot:

```text
persist.sys.adb_enable=1
persist.internet_adb_enable=1
persist.vendor.adbmode.enable=1
service.adb.tcp.port=5555
init.svc.adbd=running
```

The Android switcher requires the RSA-authenticated form of this TCP listener.
On this userdebug build, `ro.adb.secure=1` is loaded from `/data/local.prop`
at boot. Both the host and the switcher use their own authorized public keys.
The switcher connects to `127.0.0.1:5555` and uses the device's `su 0`.

When manually preloading a host public key into `/data/misc/adb/adb_keys`,
write one key per line and end the file with a newline. A missing newline
caused Android to append the switcher's authorized key to the host's line.
After separating the two keys, the switcher retained its authorization across
reboot. Select 「一律允许」 when Android prompts for the switcher's key for the
first time. [Switcher setup and usage](../tools/system-switcher/README.md)

An earlier reboot test was done with `persist.vendor.adbmode.enable=0`; that
left port 5555 closed. The vendor switch above is the selected mechanism for
keeping `adbd` alive at boot; no separate init hook is used. A userdata
reset/factory reset clears
`/data/property/persistent_properties`, so the persisted properties and the
ADB authorization must be reapplied after such a reset.

Verification:

```bash
adb connect 192.168.123.128:5555
adb devices
adb -s 192.168.123.128:5555 shell getprop persist.sys.adb_enable
adb -s 192.168.123.128:5555 shell getprop persist.internet_adb_enable
adb -s 192.168.123.128:5555 shell getprop persist.vendor.adbmode.enable
adb -s 192.168.123.128:5555 shell getprop service.adb.tcp.port
adb -s 192.168.123.128:5555 shell getprop ro.adb.secure  # 1 for the switcher
```

## Desktop

Installed APK: `~/Downloads/aerial-standard.apk`

- Package: `app.orchard.tv`
- Activity: `app.orchard.tv/.MainActivity`
- Daydream service: `app.orchard.tv/.dream.AerialDream`

Commands used:

```bash
adb -s 192.168.123.128:5555 install -r ~/Downloads/aerial-standard.apk
adb -s 192.168.123.128:5555 shell cmd package set-home-activity app.orchard.tv/.MainActivity
adb -s 192.168.123.128:5555 shell cmd role add-role-holder android.app.role.HOME app.orchard.tv
adb -s 192.168.123.128:5555 shell pm disable-user --user 0 com.android.provision
```

`com.android.provision` is disabled because its `DefaultActivity` has higher
priority for the HOME category.

Verify:

```bash
adb -s 192.168.123.128:5555 shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.HOME
```

Expected: `app.orchard.tv/.MainActivity`.

## Keyboard layout

After the p20/userdata reset, inputflinger was not loading
`/system/usr/keylayout/Generic.kl` (it is inside the `/system` overlay), so the
keyboard produced raw events but no Android key codes.

The working layout is the original 9232-byte `Generic.kl`, installed in the
vendor partition (survives a userdata reset) and in `/data` as a fallback:

- `/vendor/usr/keylayout/Generic.kl`
- `/vendor/usr/keylayout/Vendor_258a_Product_0001.kl`

- `/data/system/devices/keylayout/Generic.kl`
- `/data/system/devices/keylayout/Vendor_258a_Product_0001.kl`
- `/data/system/devices/keylayout/SINO_WEALTH_USB_KEYBOARD.kl`

The vendor-specific files have:

```text
key 1     BACK
key 125   META_LEFT
key 126   META_RIGHT
```

Meaning: Esc = Back; Win keys remain their original Meta keys.

Host backup:

- `~/Downloads/cloudbook-keylayout-backup/Generic.kl.original`
- `~/Downloads/cloudbook-keylayout-backup/Generic.kl.data`
- `~/Downloads/cloudbook-keylayout-backup/Vendor_258a_Product_0001.kl`

Restore after a userdata reset:

```bash
adb -s 192.168.123.128:5555 shell su 0 mount -o rw,remount /vendor
adb -s 192.168.123.128:5555 push \
  ~/Downloads/cloudbook-keylayout-backup/Generic.kl.data \
  /data/local/tmp/Generic.kl
adb -s 192.168.123.128:5555 push \
  ~/Downloads/cloudbook-keylayout-backup/Vendor_258a_Product_0001.kl \
  /data/local/tmp/Vendor_258a_Product_0001.kl
adb -s 192.168.123.128:5555 shell su 0 sh -c \
  'mkdir -p /data/system/devices/keylayout; \
   cp /data/local/tmp/Generic.kl /data/system/devices/keylayout/Generic.kl; \
   cp /data/local/tmp/Vendor_258a_Product_0001.kl \
     /data/system/devices/keylayout/Vendor_258a_Product_0001.kl; \
   chown system:system /data/system/devices/keylayout/*.kl; \
   chmod 644 /data/system/devices/keylayout/*.kl; \
   restorecon -R /data/system/devices; \
   mkdir -p /vendor/usr/keylayout; \
   cp /data/local/tmp/Generic.kl /vendor/usr/keylayout/Generic.kl; \
   cp /data/local/tmp/Vendor_258a_Product_0001.kl \
     /vendor/usr/keylayout/Vendor_258a_Product_0001.kl; \
   chown root:root /vendor/usr/keylayout/*.kl; \
   chmod 644 /vendor/usr/keylayout/*.kl; \
   restorecon -R /vendor/usr/keylayout'
```

Then reboot, or force the USB keyboard to reload:

```bash
adb -s 192.168.123.128:5555 shell su 0 sh -c \
  'echo -n 7-1 > /sys/bus/usb/drivers/usb/unbind; sleep 1; \
   echo -n 7-1 > /sys/bus/usb/drivers/usb/bind'
```

Verify:

```bash
adb -s 192.168.123.128:5555 shell dumpsys input | grep KeyLayoutFile
```

The SINO keyboard should show
`/vendor/usr/keylayout/Vendor_258a_Product_0001.kl` (or the `/data` fallback).

## Disabled system components

Disabled for user 0:

| Package | Reason |
| --- | --- |
| `com.android.provision` | setup wizard, intercepts HOME |
| `com.android.rockchip.camera2` | vendor camera; AOSP camera remains |
| `com.cmss.cloudcomputer` | preloaded cloud desktop client |
| `com.zte.installer` | vendor installer; AOSP installer remains |
| `com.zte.usmartlauncher` | original launcher |
| `com.zte.usmartsettings` | vendor settings/Fn hub |
| `com.zte.usmarttool` | vendor diagnostics |
| `org.chromium.webview_shell` | sample WebView shell |

Uninstalled for user 0:

| Package | Reason |
| --- | --- |
| `com.zte.usmartagent` | TR-069/system management agent |
| `com.zte.usmartupdate` | vendor OTA updater |
| `com.cghs.stresstest` | factory stress test |

Packages that were already absent after the p20 reset and were not disabled:

- `com.zte.usmartview`
- `com.DeviceTest`

Install configuration:

```bash
adb -s 192.168.123.128:5555 shell su 0 setprop persist.sys.custconf normal
```

This is required because the modified AOSP installer routes install requests
to `com.zte.installer` when `persist.sys.custconf=MB`. With `normal`, APK
installation uses the normal AOSP `PackageInstaller` flow.

The value must be set after every boot on this firmware. A vendor boot hook
has been added:

```text
# /vendor/etc/init/cloudbook_setup.rc
on property:sys.boot_completed=1
    setprop persist.sys.custconf normal
```

## Dangbei Market

Installed APK: `~/Downloads/dangbeimarket.apk`

- Package: `com.dangbeimarket`
- Version: `6.0.6`
- Install source app-op: `REQUEST_INSTALL_PACKAGES=allow`

With `persist.sys.custconf=normal` and `com.zte.installer` disabled, Dangbei's
install request goes to the AOSP `PackageInstaller` instead of the removed
ZTE installer broadcast path.

## Fn volume helper

The userdata reset removed the previous helper. It has been rebuilt and
installed as:

- Package: `com.kuaner.fnkeys` (`Fn 快捷键`)
- Target SDK: 25 (needed to receive the system Fn broadcast while backgrounded)
- No accessibility service
- Handles `com.zte.usmartview.fnkey.event`: `Fn+F4` mute, `Fn+F5` volume down,
  `Fn+F6` volume up
- Uses the original `uSmartSettings` `fn_speak_*` / `fn_ing_*` assets for the
  bottom-center overlay
- Overlay permission: `SYSTEM_ALERT_WINDOW=allow`

The app must be launched once after install so it leaves the stopped state:

```bash
adb -s 192.168.123.128:5555 install -r fnkeys.apk
adb -s 192.168.123.128:5555 shell appops set com.kuaner.fnkeys SYSTEM_ALERT_WINDOW allow
adb -s 192.168.123.128:5555 shell am start -n com.kuaner.fnkeys/.MainActivity
```

## Bluetooth MAC

- `ro.bt.bdaddr_path=/data/misc/bluetooth/bdaddr`
- The file has been recreated after the userdata reset.
- Current Bluetooth MAC: `B0:AC:82:32:D0:A4`
- Current Wi-Fi MAC: `B0:AC:82:32:D0:A3` (Bluetooth is Wi-Fi + 1)

The HAL's `BluetoothAddress::get_local_address` was disassembled: it reads
**17 characters of text** from `ro.bt.bdaddr_path` and parses it with
`%02hhx:%02hhx:...`. It is not a 6-byte binary file.

Restored file:

- Path: `/data/misc/bluetooth/bdaddr`
- Content: `B0:AC:82:32:D0:A4` (17 bytes, no newline)
- Owner/group: `bluetooth:bluetooth`, mode `0660`
- SELinux label: `u:object_r:bluetooth_data_file:s0`
- Fallback property: `persist.service.bdroid.bdaddr=B0:AC:82:32:D0:A4`

Host backup: `~/Downloads/cloudbook-bdaddr.txt`

Restore:

```bash
adb -s 192.168.123.128:5555 push \
  ~/Downloads/cloudbook-bdaddr.txt /data/local/tmp/bdaddr
adb -s 192.168.123.128:5555 shell su 0 sh -c \
  'cp /data/local/tmp/bdaddr /data/misc/bluetooth/bdaddr; \
   chown bluetooth:bluetooth /data/misc/bluetooth/bdaddr; \
   chmod 660 /data/misc/bluetooth/bdaddr; \
   restorecon /data/misc/bluetooth/bdaddr; \
   setprop persist.service.bdroid.bdaddr B0:AC:82:32:D0:A4'
```

The HAL also contains fallbacks for `ro.boot.btmacaddr`, factory BDA, and
`/dev/vflash` vendor storage. The file did not auto-regenerate because this
HAL treats it as an optional override/cache; it reads the file but does not
write it. The restored file now pins the current address explicitly.

However, the file being under `/data` is still a vendor design weakness: on
devices whose HAL relies only on that path, a userdata reset can change the
Bluetooth MAC. There is no `/persist` partition on this device. If a fixed MAC
is required, pin the current value into the vendor/NVRAM path or write the
correct HAL-specific `bdaddr` format; this has not been done.

Verify:

```bash
adb -s 192.168.123.128:5555 shell pm list packages -d --user 0
adb -s 192.168.123.128:5555 shell getprop persist.sys.custconf
```

## Re-enable / rollback

Re-enable a disabled package:

```bash
adb -s 192.168.123.128:5555 shell pm enable --user 0 <package>
```

Restore an uninstalled system package:

```bash
adb -s 192.168.123.128:5555 shell su 0 cmd package install-existing --user 0 <package>
```

Restore the original HOME selection:

```bash
adb -s 192.168.123.128:5555 shell pm enable --user 0 com.android.provision
adb -s 192.168.123.128:5555 shell cmd role remove-role-holder android.app.role.HOME app.orchard.tv
```

Disable network ADB:

```bash
adb -s 192.168.123.128:5555 shell su 0 setprop persist.internet_adb_enable 0
adb -s 192.168.123.128:5555 shell su 0 setprop persist.sys.adb_enable 0
adb -s 192.168.123.128:5555 shell su 0 setprop persist.vendor.adbmode.enable 0
```

## Notes and trade-offs

- Disabling `com.zte.usmartsettings` removes the vendor F1/F7-F12 functions.
  Fn volume/mute are provided by the lightweight helper `com.kuaner.fnkeys`.
- Network ADB uses the current DHCP address. If the router gives a different
  address after reboot, reconnect with the new IP.
- Reapplying this document after a userdata reset requires reauthorizing the
  host for ADB ("Allow debugging") once.

## What a p20/userdata wipe destroys

`p20` is the Android `userdata` partition (`/dev/block/by-name/userdata`).
Wiping it destroys `/data`, including:

- All third-party apps and app data/logins
- `/sdcard` internal storage (photos, Downloads, documents, media)
- Accounts, saved Wi-Fi networks/passwords, Bluetooth pairings
- VPNs, user certificates/keystore, lockscreen credentials
- ADB authorization keys and persisted properties
- Default apps/roles, disabled package states, permissions/AppOps, DND,
  input method, usage stats, recents, overlays/themes
- The `/data/misc/bluetooth/bdaddr` file and Android ID

System/vendor/factory partitions, serial number, firmware, and the factory
Wi-Fi/Bluetooth base address are not in p20. The repository's
`factory_backup/` contains boot/uboot/trust/dtbo/vbmeta/misc images but no
p20/userdata image, so old `/data` content is not recoverable from it.
