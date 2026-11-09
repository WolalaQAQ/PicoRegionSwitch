# PICO Region Switch

[简体中文](README_zh.md)

Switch eight official regional apps between CN and global directly on the headset:
Matrix, Store, User Center, Fitness, Friends, Home, Avatar and Swift body tracking.
One Magisk ZIP contains the panel, background worker and both APK sets. Daily
switching is offline and requires neither a PC nor a separate APK installation.

Each region keeps its own application data. The worker calls the official regional
service without upgrading the OS or changing base regional properties, language,
time zone or the factory country file. Wi-Fi country follows the official service.

**`0.6.0-dev.1` is a development release.** First installation from an uninitialized
tool state, preparation recovery, first-boot verification, CN/global roundtrips,
panel-independent completion, fault rollback and overlay removal/startup have been
validated on a PICO 4 Pro running CN OS 5.13.7. Hardware testing used the listed CN
update combination; other stock APK combinations have not each been installed.
Existing body-tracking/VRChat acceptance used the same official regional APKs.

## First installation

Requires a rooted PICO 4 Pro with Magisk, CN OS 5.13.7, Android user 0 and supported
CN apps. The installer checks the original framework, Fitness base and known
official APK identities. The firmware's stock apps and explicitly listed CN
updates are accepted; unknown versions stop before apps are paused or data handled.

1. Exit Virtual Desktop and save work in regional apps. Keep sufficient free space
   for original APKs and a complete backup of the eight apps' data.
2. In Magisk's Modules page, install `PicoRegionSwitch-<version>.zip`.
3. Wait for success. The installer preserves the current CN APKs, framework,
   Fitness base and a full application-data TAR, then atomically reinstalls the
   identical APKs as data updates. It does not switch regions or move app data.
4. Reboot once after successful installation. Open **区域切换** in the app library
   and wait for first-boot verification.
5. Select the global region when ready. Sign-in and height settings belong to the
   corresponding official services.

Returning to CN uses the CN APK versions saved from that headset, not a forced
replacement with the maintainer's versions. One region's code does not run on the
other region's saved database. A bounded downgrade window applies only to seven
listed packages; official APK signature verification remains enabled. Swift's
regional variants must have matching version codes.

## Switching and updates

Exit VD, open the panel, choose CN or global, and wait for completion before
starting streaming. Regional apps restart, but the headset does not. Closing the
panel does not stop the independent worker.

For updates, install the new ZIP in Magisk and reboot once. Existing banks and the
selected region are retained. Legacy seven-component installations additionally
use **完成八组件升级** in the same panel. Interrupted operations must be recovered
before updating.

## Recovery and removal

Switch failures attempt to restore the source apps and data. Use the panel's
recovery button if needed; do not delete journals or banks. First-boot verification
has its own button and does not switch regions. For pre-reboot installation
failures and startup rescue, see [Recovery](RECOVERY.md).

To remove, use **准备卸载模块** in the panel, wait for complete CN and Fitness cache
preparation, then remove the module in Magisk and reboot. Do not disable it directly
while global is active. Both banks and recovery archives are retained. Installation
and overlay removal have hardware acceptance on the baseline described above.

## Development

[Build](BUILDING.md) · [CI and releases](RELEASING.md) · [Changelog](CHANGELOG.md)

The repository contains project source and an input identity lock, not PICO
binaries or user data. Building the full ZIP requires separately supplied official
inputs matching the lock. Original source is [MIT-licensed](LICENSE); vendor files
and build dependencies retain their own terms. See [third-party notices](THIRD_PARTY_NOTICES.md).
