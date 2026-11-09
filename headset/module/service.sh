#!/system/bin/sh
MODDIR=${0%/*}
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done
STATE=/data/adb/pico-region-switch
mkdir -p "$STATE/headset"
chmod 700 "$STATE/headset"
CLASSPATH="$MODDIR/system/app/PicoRegionSwitch/PicoRegionSwitch.apk" \
  app_process /system/bin com.pico4.regionswitch.Backend --serve "$MODDIR" \
  >>"$STATE/headset/worker.log" 2>&1 &
