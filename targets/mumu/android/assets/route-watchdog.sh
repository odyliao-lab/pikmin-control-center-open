#!/system/bin/sh
# A worker lease, not an automatic restart. Never touches unrelated controls.
token="$1"
case "$token" in ''|*[!0-9a-f-]*) exit 2;; esac
[ ${#token} -eq 36 ] || exit 2
marker=/data/local/tmp/pcc-route-owner
lease=/data/local/tmp/pikmin-route-lease.tsv
# Per-token exit acknowledgement before handing control back to the user.
trap 'printf "%s\n" "$token" > "$marker.done.$token"; chmod 0600 "$marker.done.$token"' 0
printf '%s\n' "$token" > "$marker.ready"
chmod 0600 "$marker.ready"
pcc_fence_stop() {
( test "$(id -u)" = 0 || exit 75; for pcc_ga in /data /data/local /data/local/tmp; do test -d "$pcc_ga" && test ! -L "$pcc_ga" || exit 75; done; pcc_gd=/data/local/tmp/pcc-emulator-gps-work; pcc_gl=$pcc_gd/gps.lock; test -d "$pcc_gd" && test ! -L "$pcc_gd" && test -f "$pcc_gl" && test ! -L "$pcc_gl" || exit 75; pcc_gdir=$(stat -c %u:%g:%a:%d:%i "$pcc_gd") || exit 75; case "$pcc_gdir" in 0:0:711:*) ;; *) exit 75;; esac; pcc_glock=$(stat -c %u:%g:%a:%h:%s:%d:%i "$pcc_gl") || exit 75; case "$pcc_glock" in 0:0:600:1:0:*) ;; *) exit 75;; esac; exec 3<>"$pcc_gl" || exit 75; test -d "$pcc_gd" && test ! -L "$pcc_gd" && test "$(stat -c %u:%g:%a:%d:%i "$pcc_gd")" = "$pcc_gdir" && test -f "$pcc_gl" && test ! -L "$pcc_gl" && test "$(stat -c %u:%g:%a:%h:%s:%d:%i "$pcc_gl")" = "$pcc_glock" && test "$(stat -L -c %u:%g:%a:%h:%s:%d:%i /proc/self/fd/3 3<&3)" = "$pcc_glock" || exit 75; flock -n 3 3<&3 || exit 75; test -d "$pcc_gd" && test ! -L "$pcc_gd" && test "$(stat -c %u:%g:%a:%d:%i "$pcc_gd")" = "$pcc_gdir" && test -f "$pcc_gl" && test ! -L "$pcc_gl" && test "$(stat -c %u:%g:%a:%h:%s:%d:%i "$pcc_gl")" = "$pcc_glock" && test "$(stat -L -c %u:%g:%a:%h:%s:%d:%i /proc/self/fd/3 3<&3)" = "$pcc_glock" || exit 75; ( exec 3>&-; { pcc_rg_package=$(cmd package list packages -U --user 0 com.nianticlabs.pikmin) || exit 1; case "$pcc_rg_package" in 'package:com.nianticlabs.pikmin uid:'*) ;; *) exit 1;; esac; pcc_rg_uid=${pcc_rg_package#'package:com.nianticlabs.pikmin uid:'}; case "$pcc_rg_uid" in ''|*[!0-9]*|0*) exit 1;; esac; test "${#pcc_rg_uid}" -eq 5 || exit 1; pcc_rg_parent='/data/user/0/com.nianticlabs.pikmin/files'; test -d "$pcc_rg_parent" && test ! -L "$pcc_rg_parent" && test -r "$pcc_rg_parent" && test -x "$pcc_rg_parent" || exit 1; pcc_rg_before=$(stat -c '%u:%d:%i' "$pcc_rg_parent") || exit 1; case "$pcc_rg_before" in "$pcc_rg_uid":*) ;; *) exit 1;; esac; pcc_rg_found=$(find "$pcc_rg_parent" -mindepth 1 -maxdepth 1 -name emulator-command -print) || exit 1; test -z "$pcc_rg_found" && test ! -e "$pcc_rg_parent/emulator-command" && test ! -L "$pcc_rg_parent/emulator-command" || exit 1; pcc_rg_after=$(stat -c '%u:%d:%i' "$pcc_rg_parent") || exit 1; test "$pcc_rg_before" = "$pcc_rg_after" && test -d "$pcc_rg_parent" && test ! -L "$pcc_rg_parent" || exit 1; test "$(cmd package list packages -U --user 0 com.nianticlabs.pikmin)" = "$pcc_rg_package" || exit 1; pcc_rg_found=$(find "$pcc_rg_parent" -mindepth 1 -maxdepth 1 -name emulator-command -print) || exit 1; test -z "$pcc_rg_found" && test ! -e "$pcc_rg_parent/emulator-command" && test ! -L "$pcc_rg_parent/emulator-command" || exit 1; test "$(stat -c '%u:%d:%i' "$pcc_rg_parent")" = "$pcc_rg_before" && test -d "$pcc_rg_parent" && test ! -L "$pcc_rg_parent" || exit 1; printf 'absent\n'; } >/dev/null || exit 1;     [ "$(cat "$marker" 2>/dev/null)" = "$token" ] || exit 0
  valid=$(awk -v token="$token" -v now="$(date +%s)" 'NF==6 && $1=="v2" && $4==token && length($6)==64 && $6 !~ /[^0-9a-f]/ && $3>now*1000 && $3<=now*1000+15000 {print "yes"}' "$lease" 2>/dev/null)
    if [ "$valid" = yes ]; then exit 70; fi

    # Keep marker as interrupted-work evidence; a new run requires recovery.
    printf 'off\n' > /data/local/tmp/pikmin-dispatch-mode.txt
    printf 'off\n' > /data/local/tmp/pikmin-planting-mode.txt
    printf 'off\n' > /data/local/tmp/pikmin-flower-farm-mode.txt
    chmod 0644 /data/local/tmp/pikmin-dispatch-mode.txt /data/local/tmp/pikmin-planting-mode.txt /data/local/tmp/pikmin-flower-farm-mode.txt
    # Pause only an existing service. Never STOP GPS or launch its main screen.
    # Failure keeps owner/pending for explicit recovery; command submission is not proof.
    service=com.thejhijuqoqy.bdujvrwdcajvzin/com.theappninjas.fakegpsjoystick.service.OverlayService
    if dumpsys activity services "$service" | grep -q 'ServiceRecord{'; then
      timeout 5 am start-foreground-service --user 0 -a theappninjas.gpsjoystick.ROUTE_PAUSE -n "$service"
    fi
 ); pcc_gr=$?; test -d "$pcc_gd" && test ! -L "$pcc_gd" && test "$(stat -c %u:%g:%a:%d:%i "$pcc_gd")" = "$pcc_gdir" && test -f "$pcc_gl" && test ! -L "$pcc_gl" && test "$(stat -c %u:%g:%a:%h:%s:%d:%i "$pcc_gl")" = "$pcc_glock" && test "$(stat -L -c %u:%g:%a:%h:%s:%d:%i /proc/self/fd/3 3<&3)" = "$pcc_glock" || exit 75; exit "$pcc_gr" )
}
while [ "$(cat "$marker" 2>/dev/null)" = "$token" ]; do
  valid=$(awk -v token="$token" -v now="$(date +%s)" 'NF==6 && $1=="v2" && $4==token && length($6)==64 && $6 !~ /[^0-9a-f]/ && $3>now*1000 && $3<=now*1000+15000 {print "yes"}' "$lease" 2>/dev/null)
  if [ "$valid" != yes ]; then
    pcc_fence_stop
    pcc_fence_result=$?
    if [ "$pcc_fence_result" -eq 70 ]; then sleep 1; continue; fi
    exit "$pcc_fence_result"
  fi
  sleep 1
done
