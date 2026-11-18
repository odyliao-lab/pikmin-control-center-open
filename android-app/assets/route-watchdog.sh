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
while [ "$(cat "$marker" 2>/dev/null)" = "$token" ]; do
  valid=$(awk -v token="$token" -v now="$(date +%s)" 'NF==6 && $1=="v2" && $4==token && length($6)==64 && $6 !~ /[^0-9a-f]/ && $3>now*1000 && $3<=now*1000+15000 {print "yes"}' "$lease" 2>/dev/null)
  if [ "$valid" != yes ]; then
    [ "$(cat "$marker" 2>/dev/null)" = "$token" ] || exit 0
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
    exit 0
  fi
  sleep 1
done
