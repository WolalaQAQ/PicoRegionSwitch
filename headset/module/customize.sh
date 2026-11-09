#!/system/bin/sh
SKIPUNZIP=0
STATE=/data/adb/pico-region-switch
[ "$ARCH" = arm64 ] || abort '! Requires arm64 PICO 4 Pro.'
[ "$BOOTMODE" = true ] || abort '! Install from the running Magisk app, not recovery.'
[ -x /data/adb/magisk/busybox ] || abort '! Magisk BusyBox is required.'
[ "$(getprop sys.boot_completed)" = 1 ] || abort '! Wait for Android startup to finish.'
[ -z "$(pidof VirtualDesktop.Android)" ] || abort '! Exit Virtual Desktop before installation.'
for name in pending.json headset-pending.json headset-busy guard-current; do
  [ ! -e "$STATE/$name" ] || abort "! Recover the active transaction first: $name"
done
umask 077
mkdir -p "$STATE/headset"
chmod 700 "$STATE" "$STATE/headset"
if [ -f "$STATE/current" ]; then
  CLASSPATH="$MODPATH/system/app/PicoRegionSwitch/PicoRegionSwitch.apk" \
    app_process /system/bin com.pico4.regionswitch.FirstInstall --check-update "$MODPATH" || abort '! Recover the first installation before updating.'
  [ -f "$STATE/framework.json" ] && [ -f "$STATE/fitness-base.json" ] && [ -f "$STATE/fitness-cache.json" ] || abort '! Incomplete support state; recover before updating.'
  case "$(cat "$STATE/current")" in cn|global) ;; *) abort '! Unknown current profile.' ;; esac
  live=$(sha256sum /system/framework/services.jar)
  case "$live" in 222d3f66a9556571b1a6b69f97171f93a090698e301d8e79f1a0aecd273a5102*) ;; *) abort '! Loaded framework differs from the audited patch.' ;; esac
  ui_print '- Updating the existing installation; banks and selection are preserved.'
else
  ui_print '- First installation: verify CN originals, preserve APKs/data and prepare recovery.'
  CLASSPATH="$MODPATH/system/app/PicoRegionSwitch/PicoRegionSwitch.apk" \
    app_process /system/bin com.pico4.regionswitch.FirstInstall --prepare "$MODPATH" || \
    abort '! First installation stopped. Keep the installation record and backups; do not reboot or delete them.'
fi
set_perm_recursive "$MODPATH" 0 0 0755 0644
chcon -R u:object_r:system_file:s0 "$MODPATH/system"
set_perm_recursive "$MODPATH/packages" 0 0 0700 0600
set_perm "$MODPATH/bin/region-rename" 0 0 0700
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
for name in cn global upgrade-source-cn upgrade-source-global; do set_perm "$MODPATH/install-$name.sh" 0 0 0700; done
cp "$MODPATH/bin/region-rename" "$STATE/region-rename"
chmod 700 "$STATE/region-rename"
ui_print '- One ZIP, one headset panel. Reboot once when installation succeeds.'
ui_print '- First boot verifies the installation; it never switches regions automatically.'
