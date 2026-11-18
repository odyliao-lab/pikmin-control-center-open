#!/usr/bin/env bash
set -eu
write_template=$1
restore_template=$2
fixture=$3
target=${4:-off}
case "$target" in off) original=on;; on) original=off;; *) exit 1;; esac
case "$fixture" in */pcc-module-proof-*) ;; *) exit 1;; esac
test -d "$fixture"
n=0
for action in write restore; do
  for fault in none write chown chmod restorecon sync mv after_mv external; do
    n=$((n+1)); base="$fixture/copy-$target-$n"; mkdir -p "$base/game" "$base/backup/controls"
    file="$base/game/gps_copy_mode.txt"
    printf '%s\n' "$original" > "$base/backup/controls/gps_copy_mode.txt"
    if [ "$action" = write ]; then printf '%s\n' "$original" > "$file"; template=$write_template; initial_mode=600; else printf '%s\n' "$target" > "$file"; template=$restore_template; initial_mode=644; fi
    cp "$file" "$base/before"
    sed -e "s#/data/user/0/com.nianticlabs.pikmin/files#$base/game#g" \
        -e "s#/data/adb/pcc-core-backups/11111111-1111-1111-1111-111111111111#$base/backup#g" \
        -e 's#/system/bin/restorecon#restorecon#g' "$template" > "$base/probe.sh"
    # Ownership/mode/SELinux are mocked because NTFS/Git Bash is not Android.
    # Actual bytes, SHA-256, prior-state recheck and same-directory rename execute for real.
    cat > "$base/driver.sh" <<'DRIVER'
set -e
declare -A modes
modes["$CONTROL"]=$INITIAL_MODE
stat() { command printf '10001:10001:%s\n' "${modes[$3]:-600}"; }
chown() { [ "$FAULT" != chown ]; }
chmod() { [ "$FAULT" != chmod ]; modes["$2"]=$1; }
restorecon() { [ "$FAULT" != restorecon ]; if [ "$FAULT" = external ]; then command printf 'user\n' > "$CONTROL"; fi; }
sync() { [ "$FAULT" != sync ]; }
printf() { if [ "$FAULT" = write ]; then command printf x; return 1; fi; command printf "$@"; }
cat() { if [ "$FAULT" = write ]; then command printf x; return 1; fi; command cat "$@"; }
mv() { [ "$FAULT" != mv ]; local src=$2 dest=$3; command mv "$@"; modes["$dest"]=${modes[$src]}; if [ "$FAULT" = after_mv ]; then exit 77; fi; }
source "$PROBE"
DRIVER
    if CONTROL="$file" INITIAL_MODE="$initial_mode" FAULT="$fault" PROBE="$base/probe.sh" bash "$base/driver.sh" > "$base/result" 2>&1; then status=pass; else status=fail; fi
    if [ "$fault" = none ]; then expected=pass; else expected=fail; fi
    if [ "$status" != "$expected" ]; then command cat "$base/result"; echo "FAIL copy $action/$fault status"; exit 1; fi
    if [ "$fault" = none ] || [ "$fault" = after_mv ]; then
      if [ "$action" = write ]; then printf '%s\n' "$target" > "$base/expected"; else printf '%s\n' "$original" > "$base/expected"; fi
    elif [ "$fault" = external ]; then printf 'user\n' > "$base/expected"
    else cp "$base/before" "$base/expected"; fi
    cmp -s "$file" "$base/expected" || { echo "FAIL copy $action/$fault changed original unexpectedly"; exit 1; }
    echo "PASS atomic copy fixture: $target/$action/$fault"
  done
done
echo "PASS $n copy byte/rename fault fixtures; mocked owner/mode/SELinux, NOT Android transaction validation"
