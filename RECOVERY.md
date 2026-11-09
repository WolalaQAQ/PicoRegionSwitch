# Recovery

## Interrupted switch

The panel's **恢复未完成的切换** restores the source APKs, bank roots, region,
enabled states and services. Its journal pins the original executor, install plan
and source/target APKs; updating filenames or deleting `headset-pending.json` is
not a recovery procedure. The worker also attempts this recovery after restarting.

## First-install failure before reboot

Installation preparation does not switch regions or move application data. Its
APK reinstall uses identical source bytes. A failure retains
`/data/adb/pico-region-switch/first-install.json` and its exact backup directory
under `/data/adb/pico-region-switch/headset/first-install-<id>`.

Do not reboot after Magisk reports an installation failure. If the original OS
is still running, use a root shell to read `first-install.json`, then run the
repository's `tools/restore-first-install.sh` with the exact `run` directory:

```sh
sh restore-first-install.sh /data/adb/pico-region-switch/headset/first-install-<id>
```

The script runs the saved executor, whose identity must match the journal. It
verifies source APKs and data roots, restores app runtime state and any missing
preserved parser cache, and removes only the incomplete initialization markers.
It keeps the journal, original APKs and complete data TAR. A later installation
archives the restored journal before preparing a new one.

If no journal was created, installation stopped before app changes. Preserve the
Magisk error text to identify the unsupported baseline or missing prerequisite.

## First boot

The worker verifies a changed boot ID, loaded framework/Fitness mapping, preserved
CN APKs, backup identity, app-data roots and runtime health. Partial support-marker
writes can be completed from the same saved record after these checks. A failure
keeps switching disabled; **验证首次安装** retries verification, not installation.

## Startup rescue

If Android cannot finish startup after installing the overlay, disable
`pico-region-downgrade` using Magisk's recovery mechanism or an available root ADB
shell, then reboot into the unmodified system partition. The module changes no
bootloader or firmware partition. First installation preserved identical CN APKs
as data updates before loading the mapping, so disabling the overlay does not
require exposing global Fitness to CN data.

When using an available root shell, the precise disable marker is:

```sh
touch /data/adb/modules/pico-region-downgrade/disable
```

After the original framework is loaded again, use the saved executor to restore
installation preparation. Do not factory-reset or delete banks to resolve a
framework overlay problem. Overlay disable and return to the original framework
have been verified on the supported headset. A deliberately failed boot and its
rescue have not been induced in this release.

## Normal removal

Choose **准备卸载模块** in the panel. It returns to that device's saved CN profile
and preserves/invalidates only the Fitness parser cache. Remove the module in
Magisk and reboot after the successful preparation message. Both regional banks
and transaction backups remain on the device. This is not a request to restore
the entire device to an older snapshot: current CN data is retained.
