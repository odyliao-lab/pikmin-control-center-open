#!/usr/bin/env bash
set -eu
template=$1
fixture=$2
case "$fixture" in */pcc-module-proof-*) ;; *) exit 1;; esac
test -d "$fixture"
n=0
run_case() {
  name=$1; expected=$2; n=$((n+1)); base="$fixture/case$n"
  core="$base/modules/pikmin_nectar_rpc"
  mkdir -p "$core/zygisk" "$base/modules_update"
  printf 'versionCode=67\n' > "$core/module.prop"
  printf old-so > "$core/zygisk/arm64-v8a.so"
  printf old-service > "$core/service.sh"
  case "$name" in
    new|new-old-meta) printf new-so > "$core/zygisk/arm64-v8a.so"; printf new-service > "$core/service.sh" ;;
    corrupt) printf unexpected > "$core/zygisk/arm64-v8a.so" ;;
    mixed) printf new-so > "$core/zygisk/arm64-v8a.so" ;;
    missing) mv "$core/service.sh" "$base/saved-service" ;;
  esac
  case "$name" in
    new|old-new-meta) printf 'versionCode=68\n' > "$core/module.prop" ;;
    unknown) printf 'versionCode=69\n' > "$core/module.prop" ;;
  esac
  sed "s#/data/adb#$base#g" "$template" > "$base/probe.sh"
  if bash "$base/probe.sh" > "$base/result" 2>&1; then status=pass; else status=fail; fi
  if [ "$status" != "$expected" ]; then cat "$base/result"; echo "FAIL $name"; exit 1; fi
  echo "PASS module proof fixture: $name ($status)"
}
run_case old pass
run_case new pass
run_case old-new-meta pass
run_case new-old-meta pass
run_case corrupt fail
run_case mixed fail
run_case missing fail
run_case unknown fail
echo "PASS $n host filesystem fixtures; NOT Android namespace/SELinux/Magisk validation"
