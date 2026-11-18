#!/system/bin/sh
# Engineering-only independent deadline. No production app or game modifications.
case "$1" in ''|*[!a-z0-9-]*) exit 2;; esac
[ ${#1} -ge 8 ] && [ ${#1} -le 60 ] || exit 2
marker="/data/local/tmp/pcc-route-watchdog-$1"
[ ! -e "$marker" ] || exit 3
printf 'armed\n' > "$marker" || exit 4
chmod 0600 "$marker"
sleep 75
if [ -f "$marker" ]; then
  am start-foreground-service -a theappninjas.gpsjoystick.STOP -p com.thejhijuqoqy.bdujvrwdcajvzin
  printf 'expired\n' > "$marker"
fi
