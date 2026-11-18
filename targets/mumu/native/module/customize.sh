#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort "Booted installation only"
[ "$ARCH" = x64 ] || abort "x86_64 emulator only"
[ "$(getprop ro.build.version.sdk)" = 35 ] || abort "Validated Android15 instance only"
[ "$MODPATH" = /data/adb/modules_update/pcc_emulator_bridge_canary ] || abort "Unexpected module staging path"
[ "$(getprop ro.dalvik.vm.native.bridge)" = libnb.so ] || abort "Only MuMu libnb canary is supported"
[ -z "$(pidof com.nianticlabs.pikmin)" ] || abort "Close Pikmin first"
for p in /data/adb/modules /data/adb/modules_update "$MODPATH"; do
  [ ! -L "$p" ] || abort "Unsafe module path"
done
for id in pikmin_nectar_rpc zygisk_pikmin_gps_copy; do
  for base in /data/adb/modules /data/adb/modules_update; do
    [ ! -e "$base/$id" ] && [ ! -L "$base/$id" ] || abort "Canary requires isolated module state"
  done
done
set_perm "$MODPATH/zygisk/x86_64.so" 0 0 0644
set_perm "$MODPATH/payload-arm64.so" 0 0 0644
ui_print "MuMu-only core staged. Reboot, start Pikmin, verify the same-PID report."
