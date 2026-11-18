#!/system/bin/sh
set -eu
fixture=$1
case "$fixture" in /data/local/tmp/pcc-copy-android-*) ;; *) exit 1;; esac
suffix=${fixture#/data/local/tmp/pcc-copy-android-}
test ${#suffix} = 32
case "$suffix" in *[!a-f0-9]*) exit 1;; esac
test "$(id -u)" = 0
test -d "$fixture"; test ! -L "$fixture"
n=0
for kind in absent enabled disabled large_core large_public large_copy public_dir core_symlink public_symlink copy_symlink backup_symlink tar_failure existing_archive; do
  n=$((n+1)); base="$fixture/archive$n"; state=enabled
  case "$kind" in absent|disabled) state=$kind;; esac
  core="$base/adb/modules/pikmin_nectar_rpc"
  gps="$base/adb/modules/zygisk_pikmin_gps_copy"
  backup="$base/adb/pcc-core-backups/01234567-0123-0123-0123-0123456789ab"
  mkdir -p "$core/zygisk" "$base/adb/modules_update" "$backup" "$base/public" "$base/game"
  for f in module.prop service.sh zygisk/arm64-v8a.so nectar_control_mode.txt; do printf '%s\n' "$f" > "$core/$f"; done
  printf 'do not archive\n' > "$core/runtime.tsv"
  printf 'armed\n' > "$base/public/pikmin-dispatch-mode.txt"
  printf '1000,3000\n' > "$base/public/pikmin-planter-steps.txt"
  printf 'on\n' > "$base/game/gps_copy_mode.txt"
  printf 'do not archive\n' > "$base/game/pending.tsv"
  if test "$state" != absent; then
    mkdir -p "$gps/zygisk"
    printf 'gps\n' > "$gps/module.prop"; printf 'gps-so\n' > "$gps/zygisk/arm64-v8a.so"
    if test "$state" = disabled; then touch "$gps/disable"; fi
  fi
  case "$kind" in
    large_core) head -c 65 /dev/zero > "$core/nectar_control_mode.txt";;
    large_public) head -c 4097 /dev/zero > "$base/public/pikmin-dispatch-mode.txt";;
    large_copy) head -c 65 /dev/zero > "$base/game/gps_copy_mode.txt";;
    public_dir) mkdir "$base/public/pikmin-return-mode.txt";;
    core_symlink) ln -s "$core/nectar_control_mode.txt" "$core/return_control_mode.txt";;
    public_symlink) ln -s "$base/public/pikmin-dispatch-mode.txt" "$base/public/pikmin-return-mode.txt";;
    copy_symlink) mv "$base/game/gps_copy_mode.txt" "$base/game/original"; ln -s "$base/game/original" "$base/game/gps_copy_mode.txt";;
    backup_symlink) ln -s "$base/game/gps_copy_mode.txt" "$backup/core.tar";;
    existing_archive) printf 'existing\n' > "$backup/core.tar";;
  esac
  find "$base/adb/modules" "$base/public" "$base/game" -type f -exec sha256sum {} \; | sort > "$base/before"
  sed -e "s#/data/local/tmp/#$base/public/#g" \
      -e "s#/data/adb#$base/adb#g" \
      -e "s#/data/user/0/com.nianticlabs.pikmin/files#$base/game#g" "$fixture/archive-$state.sh" > "$base/probe.sh"
  if grep -qE '/data/(user|data|adb)/' "$base/probe.sh"; then echo 'FAIL unsafe archive path'; exit 1; fi
  if test "$kind" = tar_failure; then
    printf 'set -e\ntar() { command tar "$@"; return 1; }\n. "%s"\n' "$base/probe.sh" > "$base/driver.sh"
  else printf '. "%s"\n' "$base/probe.sh" > "$base/driver.sh"; fi
  if sh "$base/driver.sh" > "$base/result" 2>&1; then status=pass; else status=fail; fi
  case "$kind" in absent|enabled|disabled) expected=pass;; *) expected=fail;; esac
  if test "$status" != "$expected"; then cat "$base/result"; echo "FAIL Android archive $kind status"; exit 1; fi
  find "$base/adb/modules" "$base/public" "$base/game" -type f -exec sha256sum {} \; | sort > "$base/after"
  cmp -s "$base/before" "$base/after"
  if test "$status" = pass; then
    test "$(stat -c %a "$backup/core.tar")" = 600
    test "$(stat -c %a "$backup/controls.tar")" = 600
    test "$(tar -tf "$backup/core.tar" | wc -l)" -eq 4
    tar -xOf "$backup/core.tar" nectar_control_mode.txt > "$base/recovered"
    cmp -s "$base/recovered" "$core/nectar_control_mode.txt"
    tar -xOf "$backup/controls.tar" controls/gps_copy_mode.txt > "$base/recovered"
    cmp -s "$base/recovered" "$base/game/gps_copy_mode.txt"
    test "$(tar -tf "$backup/controls.tar" | wc -l)" -eq 4
    if test "$state" = absent; then test ! -e "$backup/gps.tar"
    else
      test "$(stat -c %a "$backup/gps.tar")" = 600
      if test "$state" = disabled; then expected_count=3; else expected_count=2; fi
      test "$(tar -tf "$backup/gps.tar" | wc -l)" -eq "$expected_count"
    fi
  fi
  echo "PASS Android archive: $kind; source bytes unchanged"
done
echo "PASS $n isolated Android archive cases; NOT full backup journal/Magisk/reboot transaction"
