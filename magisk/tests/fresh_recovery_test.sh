#!/usr/bin/env bash
set -eu
template=$1
fixture=$2
case "$fixture" in */pcc-module-proof-*) ;; *) exit 1;; esac
test -d "$fixture"
n=0
run_case() {
  name=$1; expected=$2; n=$((n+1)); base="$fixture/fresh$n"
  core="$base/modules/pikmin_nectar_rpc"
  mkdir -p "$core" "$base/modules_update"
  printf fixture > "$core/module.prop"
  case "$name" in
    complete|corrupt-so|corrupt-service|disabled|removed)
      mkdir "$core/zygisk"; printf new-so > "$core/zygisk/arm64-v8a.so"; printf new-service > "$core/service.sh" ;;
  esac
  case "$name" in
    metadata-update) : > "$core/update" ;;
    wrong-prop) printf other > "$core/module.prop" ;;
    unknown-file) printf data > "$core/other" ;;
    hidden-file) printf data > "$core/.other" ;;
    update-data) printf data > "$core/update" ;;
    update-directory) mkdir "$core/update" ;;
    partial-library) mkdir "$core/zygisk"; printf new-so > "$core/zygisk/arm64-v8a.so" ;;
    stray-service) printf new-service > "$core/service.sh" ;;
    corrupt-so) printf corrupt > "$core/zygisk/arm64-v8a.so" ;;
    corrupt-service) printf corrupt > "$core/service.sh" ;;
    disabled) : > "$core/disable" ;;
    removed) : > "$core/remove" ;;
  esac
  sed "s#/data/adb#$base#g" "$template" > "$base/probe.sh"
  if bash "$base/probe.sh" > "$base/result" 2>&1; then status=pass; else status=fail; fi
  if [ "$status" != "$expected" ]; then cat "$base/result"; echo "FAIL fresh $name"; exit 1; fi
  echo "PASS fresh recovery fixture: $name ($status)"
}
run_case metadata pass
run_case metadata-update pass
run_case complete pass
for name in wrong-prop unknown-file hidden-file update-data update-directory partial-library stray-service corrupt-so corrupt-service disabled removed; do run_case "$name" fail; done
echo "PASS $n fresh recovery filesystem fixtures; NOT Android/Magisk/process-death/reboot validation"
