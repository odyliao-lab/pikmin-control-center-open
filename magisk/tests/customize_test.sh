#!/usr/bin/env bash
# Host fixture test. No Magisk invocation, root access or actual /data writes.
set -eu
template=$1
fixture=$2
case "$fixture" in */pcc-customize-*) ;; *) echo 'Unsafe fixture root'; exit 1;; esac
test -d "$fixture"
n=0
run_case() {
  name=$1; expected=$2; n=$((n+1))
  base="$fixture/case$n"
  mkdir -p "$base/modules/pikmin_nectar_rpc" "$base/modules_update/pikmin_nectar_rpc/zygisk"
  printf 'off\n' > "$base/modules/pikmin_nectar_rpc/nectar_control_mode.txt"
  printf 'off\n' > "$base/modules/pikmin_nectar_rpc/return_control_mode.txt"
  printf 'discard\n' > "$base/modules/pikmin_nectar_rpc/return_postcard_policy.txt"
  printf '3\n' > "$base/modules/pikmin_nectar_rpc/return_batch_limit.txt"
  touch "$base/modules_update/pikmin_nectar_rpc/service.sh" "$base/modules_update/pikmin_nectar_rpc/zygisk/arm64-v8a.so"
  if [ "$name" != absent ]; then
    mkdir -p "$base/modules/zygisk_pikmin_gps_copy"
    touch "$base/modules/zygisk_pikmin_gps_copy/disable"
  fi
  case "$name" in
    enabled) rm "$base/modules/zygisk_pikmin_gps_copy/disable" ;;
    staged) mkdir "$base/modules_update/zygisk_pikmin_gps_copy" ;;
    removing) touch "$base/modules/pikmin_nectar_rpc/remove" ;;
    directory) mkdir "$base/modules/pikmin_nectar_rpc/return_batch_limit.txt.tmp"; mv "$base/modules/pikmin_nectar_rpc/return_batch_limit.txt" "$base/original-limit"; mv "$base/modules/pikmin_nectar_rpc/return_batch_limit.txt.tmp" "$base/modules/pikmin_nectar_rpc/return_batch_limit.txt" ;;
    oversized) printf '%080d' 1 > "$base/modules/pikmin_nectar_rpc/return_batch_limit.txt" ;;
  esac
  sed "s#/data/adb#$base#g" "$template" > "$base/customize.sh"
  if (
    abort(){ echo "$1"; exit 42; }
    ui_print(){ :; }
    set_perm(){ printf '%s\t%s\n' "$1" "$4" >> "$base/perms"; chmod "$4" "$1"; }
    pidof(){ if [ "$name" = game ]; then echo 123; else return 1; fi; }
    BOOTMODE=true; ARCH=arm64; MODPATH="$base/modules_update/pikmin_nectar_rpc"
    [ "$name" != recovery ] || BOOTMODE=false
    [ "$name" != architecture ] || ARCH=x86
    . "$base/customize.sh"
  ) > "$base/result" 2>&1; then status=pass; else status=fail; fi
  if [ "$status" != "$expected" ]; then cat "$base/result"; echo "FAIL $name"; exit 1; fi
  if [ "$status" = pass ]; then
    for f in nectar_control_mode.txt return_control_mode.txt return_postcard_policy.txt return_batch_limit.txt; do
      cmp "$base/modules/pikmin_nectar_rpc/$f" "$base/modules_update/pikmin_nectar_rpc/$f"
      grep -F "$f" "$base/perms" | grep -q '0644'
    done
  fi
  echo "PASS customize fixture: $name ($status)"
}
run_case absent pass
run_case disabled pass
run_case enabled fail
run_case staged fail
run_case removing fail
run_case directory fail
run_case oversized fail
run_case game fail
run_case recovery fail
run_case architecture fail
echo "PASS $n host fixtures; NOT Android permissions, Magisk staging or boot validation"
