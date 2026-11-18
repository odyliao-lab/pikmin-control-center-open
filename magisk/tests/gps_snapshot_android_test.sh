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
for kind in absent enabled disabled staged removing disable_directory gps_symlink zygisk_symlink updates_file prop_symlink missing_so changed_prop; do
  n=$((n+1)); base="$fixture/gps$n"; gps="$base/modules/zygisk_pikmin_gps_copy"
  mkdir -p "$base/modules"
  if test "$kind" != absent; then
    mkdir -p "$gps/zygisk"
    printf 'gps\n' > "$gps/module.prop"; printf 'gps-so\n' > "$gps/zygisk/arm64-v8a.so"
  fi
  case "$kind" in
    disabled) touch "$gps/disable";;
    staged) mkdir -p "$base/modules_update/zygisk_pikmin_gps_copy";;
    removing) touch "$gps/remove";;
    disable_directory) mkdir "$gps/disable";;
    gps_symlink) mv "$gps" "$base/original"; ln -s "$base/original" "$gps";;
    zygisk_symlink) mv "$gps/zygisk" "$base/original"; ln -s "$base/original" "$gps/zygisk";;
    updates_file) touch "$base/modules_update";;
    prop_symlink) mv "$gps/module.prop" "$base/original"; ln -s "$base/original" "$gps/module.prop";;
    missing_so) mv "$gps/zygisk/arm64-v8a.so" "$base/original";;
    changed_prop) printf 'changed\n' > "$gps/module.prop";;
  esac
  sed "s#/data/adb#$base#g" "$fixture/gps-snapshot.sh" > "$base/probe.sh"
  if grep -q '/data/adb' "$base/probe.sh"; then exit 1; fi
  if sh "$base/probe.sh" > "$base/result" 2>&1; then status=pass; else status=fail; fi
  case "$kind" in absent|enabled|disabled|changed_prop) expected=pass;; *) expected=fail;; esac
  if test "$status" != "$expected"; then cat "$base/result"; echo "FAIL GPS snapshot $kind"; exit 1; fi
  if test "$status" = pass; then
    state=$kind; if test "$kind" = changed_prop; then state=enabled; fi
    if test "$state" = absent; then printf 'v1\tabsent\t-\t-' > "$base/expected"
    else printf 'v1\t%s\t%s\t%s' "$state" "$(sha256sum "$gps/module.prop" | cut -d ' ' -f1)" "$(sha256sum "$gps/zygisk/arm64-v8a.so" | cut -d ' ' -f1)" > "$base/expected"; fi
    cmp -s "$base/result" "$base/expected"
    sed "s#/data/adb#$base#g" "$fixture/gps-proof-$state.sh" > "$base/proof.sh"
    if sh "$base/proof.sh"; then proof=pass; else proof=fail; fi
    if test "$kind" = changed_prop; then test "$proof" = fail; else test "$proof" = pass; fi
  fi
  echo "PASS Android GPS snapshot/proof: $kind (no automation core present)"
done
echo "PASS $n isolated standalone GPS identity cases; NOT mixed installation/rollback acceptance"
for state in absent enabled disabled; do
  base="$fixture/fresh-gps-$state"; gps="$base/modules/zygisk_pikmin_gps_copy"
  backup="$base/pcc-core-backups/11111111-2222-3333-4444-555555555555"
  mkdir -p "$base/modules" "$backup"; chmod 700 "$backup"
  if test "$state" != absent; then
    mkdir -p "$gps/zygisk"
    printf 'gps\n' > "$gps/module.prop"; printf 'gps-so\n' > "$gps/zygisk/arm64-v8a.so"
    if test "$state" = disabled; then touch "$gps/disable"; fi
  fi
  sed "s#/data/adb#$base#g" "$fixture/gps-archive-$state.sh" > "$base/archive.sh"
  if grep -q '/data/adb' "$base/archive.sh"; then exit 1; fi
  sh "$base/archive.sh"
  if test "$state" = absent; then test ! -e "$backup/gps.tar"
  else
    test "$(stat -c %a "$backup/gps.tar")" = 600
    tar -tf "$backup/gps.tar" > "$base/members"
    grep -q 'zygisk_pikmin_gps_copy/module.prop' "$base/members"
    grep -q 'zygisk_pikmin_gps_copy/zygisk/arm64-v8a.so' "$base/members"
    if test "$state" = disabled; then grep -q 'zygisk_pikmin_gps_copy/disable' "$base/members"; fi
    before=$(sha256sum "$backup/gps.tar")
    if sh "$base/archive.sh"; then echo 'FAIL overwrote GPS backup'; exit 1; fi
    test "$before" = "$(sha256sum "$backup/gps.tar")"
  fi
  echo "PASS Android fresh GPS archive: $state, private archive/no overwrite"
done
