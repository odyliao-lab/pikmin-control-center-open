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
for target in off on; do
  if test "$target" = off; then original=on; else original=off; fi
  for action in write restore; do
    for fault in none write chown chmod restorecon sync mv after_mv external temp_symlink; do
      n=$((n+1)); base="$fixture/case$n"
      mkdir -p "$base/game" "$base/backup/controls"
      file="$base/game/gps_copy_mode.txt"
      printf '%s\n' "$original" > "$base/backup/controls/gps_copy_mode.txt"
      if test "$action" = write; then initial=$original; initial_mode=600; else initial=$target; initial_mode=644; fi
      printf '%s\n' "$initial" > "$file"
      chown 10001:10001 "$file"; chmod "$initial_mode" "$file"
      cp "$file" "$base/before"
      sed -e "s#/data/user/0/com.nianticlabs.pikmin/files#$base/game#g" \
          -e "s#/data/adb/pcc-core-backups/11111111-1111-1111-1111-111111111111#$base/backup#g" \
          -e 's#/system/bin/restorecon#restorecon#g' "$fixture/copy-$action-$target.sh" > "$base/probe.sh"
      # Refuse any missed substitution before root executes the generated command.
      if grep -qE '/data/(user|data|adb)/' "$base/probe.sh"; then echo 'FAIL unsafe production path'; exit 1; fi
      if test "$fault" = temp_symlink; then
        printf 'decoy\n' > "$base/decoy"
        ln -s "$base/decoy" "$file.pcc-11111111-1111-1111-1111-111111111111.tmp"
      fi
      if FAULT="$fault" CONTROL="$file" PROBE="$base/probe.sh" sh "$fixture/driver.sh" > "$base/result" 2>&1; then status=pass; else status=fail; fi
      if test "$fault" = none; then expected=pass; else expected=fail; fi
      if test "$status" != "$expected"; then cat "$base/result"; echo "FAIL Android copy $target/$action/$fault status"; exit 1; fi
      mode=$initial_mode
      if test "$fault" = none || test "$fault" = after_mv; then
        if test "$action" = write; then value=$target; mode=644; else value=$original; mode=600; fi
        printf '%s\n' "$value" > "$base/expected"
      elif test "$fault" = external; then printf 'user\n' > "$base/expected"
      else cp "$base/before" "$base/expected"; fi
      cmp -s "$file" "$base/expected"
      test "$(stat -c %u:%g:%a "$file")" = "10001:10001:$mode"
      if test "$fault" = temp_symlink; then test "$(cat "$base/decoy")" = decoy; fi
      echo "PASS Android copy: $target/$action/$fault"
    done
  done
done
echo "PASS $n isolated Android copy cases: actual bytes, owner/mode, rename and restorecon; NOT game-private SELinux, Magisk transaction or reboot acceptance"
