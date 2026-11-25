# Recovery Guide

**English** | [简体中文](RECOVERY_zh.md)

Use this guide when an installation, boot verification, or region switch does not finish cleanly, or when you want to remove the module safely.

## Which Situation Are You In?

| Symptom | Go To |
|---|---|
| A CN / Global switch failed or was interrupted mid-way | [Switch interrupted or failed](#switch-interrupted-or-failed) |
| Magisk reported an error during **first install** and you have **not** rebooted yet | [First install failed before reboot](#first-install-failed-before-reboot) |
| First boot finished, but the panel says first-install verification is pending or failed | [First-boot verification pending](#first-boot-verification-pending) |
| Android cannot finish booting after installing the module | [Headset cannot finish booting](#headset-cannot-finish-booting) |
| Everything works and you want to uninstall the module | [Normal uninstall](#normal-uninstall) |

> [!CAUTION]
> **Never delete `/data/adb/pico-region-switch/headset-pending.json`, `first-install.json`, or any regional bank directory** to clear an error. Those files record the exact source APKs, executor digest, and data locations needed for an exact rollback.

---

## Switch Interrupted or Failed

If a switch stops mid-way, the background worker attempts automatic rollback on restart. You can also trigger it directly from the headset:

1. Open **区域切换** in the headset app library.
2. Tap **恢复未完成的切换** and confirm.
3. Wait for the panel to report that the original region and its apps have been restored.

The recovery journal (`headset-pending.json`) pins the original executor APK digest, install plan, and source/target APK paths so recovery always restores the exact pre-switch state without touching your saved data.

> [!NOTE]
> If the panel mentions a pending PC transaction (`pending.json`), complete recovery using the PC maintenance script (`region_switch.py`) instead of the headset panel.

---

## First Install Failed Before Reboot

First-install preparation does **not** switch regions or move your app data, and its APK reinstall uses identical source bytes. When preparation fails partway through, it keeps `/data/adb/pico-region-switch/first-install.json` and the backup folder under `/data/adb/pico-region-switch/headset/first-install-<id>`.

1. **Do not reboot** while the original OS is still running.
2. Check whether a journal was created:
   ```sh
   adb shell su -c "cat /data/adb/pico-region-switch/first-install.json"
   ```
   - **If `first-install.json` does not exist**: the installer stopped during preflight checks before changing any app or file. Save the Magisk error message to see which prerequisite or APK digest did not match—no cleanup is needed.
   - **If `first-install.json` exists**: copy the exact `"run"` path from the JSON (for example, `/data/adb/pico-region-switch/headset/first-install-<id>`).
3. Push or copy [`tools/restore-first-install.sh`](tools/restore-first-install.sh) to the headset and run it as root with that exact backup directory:
   ```sh
   adb push tools/restore-first-install.sh /data/local/tmp/restore-first-install.sh
   adb shell su -c "sh /data/local/tmp/restore-first-install.sh /data/adb/pico-region-switch/headset/first-install-<id>"
   ```

The script invokes the saved `executor.apk` inside that backup directory (verifying its SHA-256 against the journal), checks your source APKs and data roots, restores app runtime state and any preserved Fitness parser cache, and removes only the incomplete initialization markers. Your journal, original APKs, and complete data TAR stay intact; a later installation archives the restored journal automatically before starting a fresh run.

---

## First-Boot Verification Pending

After the first reboot, the background worker checks:

- A new kernel boot ID (`boot_id` changed since preparation)
- The loaded patched framework (`services.jar`) and Fitness base overlay
- Preserved original CN APKs, backup digests, app-data roots, and core service health

Until these checks pass, region switching stays disabled so nothing can run on an unverified setup.

1. Wait 30–60 seconds after boot for system services to settle, then open **区域切换**.
2. Tap **验证首次安装**.

This button only reruns verification (and finishes any partial support-marker writes from the saved record). It never reinstalls apps or switches regions automatically.

---

## Headset Cannot Finish Booting

The module never modifies the bootloader or system partitions—all framework and Fitness overlays are applied dynamically by Magisk. Because first installation kept your identical CN APKs as data updates before loading the overlay, disabling the module returns cleanly to the stock framework without exposing global Fitness to CN data.

1. Disable the module using either Magisk's safe-mode/recovery mechanism or an available root ADB shell:
   ```sh
   adb shell su -c "touch /data/adb/modules/pico-region-downgrade/disable"
   adb reboot
   ```
2. Once the headset boots back into the stock framework, find the backup directory in `/data/adb/pico-region-switch/first-install.json` and run `restore-first-install.sh` as shown in [First install failed before reboot](#first-install-failed-before-reboot).
3. Remove the disabled module in Magisk if you do not intend to retry.

> [!IMPORTANT]
> Do **not** factory-reset the headset or delete regional banks to recover from a framework overlay boot issue. Disabling `pico-region-downgrade` restores the unmodified system partition immediately on the next reboot.

---

## Normal Uninstall

> [!WARNING]
> Do not disable or remove the module in Magisk while Global is active. Always run **准备卸载模块** first.

1. Exit **Virtual Desktop** and open **区域切换**.
2. Tap **准备卸载模块** and confirm.
3. Wait for the confirmation message stating that full CN is restored and the Fitness parser cache has been handled.
4. Open **Magisk -> Modules**, remove `pico-region-downgrade`, and reboot once.

Your current CN app data stays intact—uninstalling does not roll your headset back to an older snapshot. Both regional banks and recovery archives remain on disk so you do not lose saved data.
