#!/system/bin/sh
# Isolated test driver: real Android ownership/mode/labels; only selected operations fail.
set -e
test "$(id -u)" = 0
chown() { test "$FAULT" != chown; command chown "$@"; }
chmod() { test "$FAULT" != chmod; command chmod "$@"; }
restorecon() {
  test "$FAULT" != restorecon
  /system/bin/restorecon "$@"
  if test "$FAULT" = external; then command printf 'user\n' > "$CONTROL"; fi
}
sync() { test "$FAULT" != sync; command sync; }
printf() { if test "$FAULT" = write; then command printf x; return 1; fi; command printf "$@"; }
cat() { if test "$FAULT" = write; then command printf x; return 1; fi; command cat "$@"; }
mv() { test "$FAULT" != mv; command mv "$@"; if test "$FAULT" = after_mv; then exit 77; fi; }
. "$PROBE"
