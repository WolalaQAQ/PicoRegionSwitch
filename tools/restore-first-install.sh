#!/system/bin/sh
# Run as root, with the exact backup directory shown in first-install.json.
set -eu
case "${1:-}" in /data/adb/pico-region-switch/headset/first-install-*) ;; *) echo 'Usage: sh restore-first-install.sh BACKUP_DIRECTORY'; exit 1 ;; esac
[ -f "$1/executor.apk" ]
CLASSPATH="$1/executor.apk" app_process /system/bin com.pico4.regionswitch.FirstInstall \
  --restore-preparation /data/adb/modules/pico-region-downgrade
