#!/system/bin/sh
# Invoked by Magisk after extraction, before it changes the installed module metadata.
# Do not disable another module here: the app's durable migration transaction owns that.
[ "$BOOTMODE" = true ] || abort "Install from booted Android through Control Center"
[ "$ARCH" = arm64 ] || abort "Only arm64 is supported"
[ "$MODPATH" = /data/adb/modules_update/pikmin_nectar_rpc ] || abort "Unexpected module staging path"
[ -z "$(pidof com.nianticlabs.pikmin)" ] || abort "Close Pikmin before installing the core"
PCC_OLD=/data/adb/modules/pikmin_nectar_rpc
PCC_GPS=/data/adb/modules/zygisk_pikmin_gps_copy
for p in /data/adb/modules /data/adb/modules_update "$MODPATH" "$PCC_OLD" "$PCC_GPS"; do
  [ ! -L "$p" ] || abort "Unsafe module symlink"
done
[ -d /data/adb/modules ] || abort "Module namespace unavailable"
for p in /data/adb/modules_update/zygisk_pikmin_gps_copy "$PCC_OLD/remove" "$PCC_GPS/remove"; do
  [ ! -e "$p" ] && [ ! -L "$p" ] || abort "Conflicting pending module state"
done
if [ -e "$PCC_GPS" ]; then
  [ -d "$PCC_GPS" ] && [ -f "$PCC_GPS/disable" ] && [ ! -L "$PCC_GPS/disable" ] || abort "Legacy GPS Copy must be safely disabled first"
fi

# External/game-private controls and histories are intentionally not modified.
# The old service normally supplies these four module-private controls; preserve
# their exact contents rather than silently resetting them at next boot.
for name in nectar_control_mode.txt return_control_mode.txt return_postcard_policy.txt return_batch_limit.txt; do
  source="$PCC_OLD/$name"
  [ ! -L "$source" ] || abort "Unsafe control symlink"
  if [ -e "$source" ]; then
    [ -f "$source" ] || abort "Unexpected control type"
    [ "$(wc -c < "$source")" -le 64 ] || abort "Oversized control"
    cp -p "$source" "$MODPATH/$name" || abort "Cannot preserve control"
    cmp -s "$source" "$MODPATH/$name" || abort "Control changed during copy"
    set_perm "$MODPATH/$name" 0 0 0644 || abort "Cannot set control permission"
  fi
done
set_perm "$MODPATH/service.sh" 0 0 0755 || abort "Cannot set service permission"
set_perm "$MODPATH/zygisk/arm64-v8a.so" 0 0 0644 || abort "Cannot set library permission"
ui_print "Core staged; reboot and verify automation plus GPS capability in Control Center."
