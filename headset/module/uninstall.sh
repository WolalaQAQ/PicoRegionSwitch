#!/system/bin/sh
# The panel returns to CN and invalidates only the Fitness cache before removal.
# Installation originals, both banks and transaction backups survive removal.
STATE=/data/adb/pico-region-switch
echo 'Module removed; regional banks and recovery backups retained.' >> "$STATE/headset/worker.log"
