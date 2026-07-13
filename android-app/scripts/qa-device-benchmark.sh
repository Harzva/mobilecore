#!/usr/bin/env bash
set -euo pipefail

PACKAGE="com.mobilecore.app"
ACTIVITY="ai.mobilecore.MainActivity"
PROFILE="${PROFILE:-standard}"
SERIAL="${ANDROID_SERIAL:-}"
HOST_PORT="${HOST_PORT:-18081}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-2400}"
ALLOW_EMULATOR="${ALLOW_EMULATOR:-false}"
OUTPUT_ROOT="${OUTPUT_ROOT:-artifacts/device-qa}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUTPUT_DIR="$OUTPUT_ROOT/$STAMP"

case "$PROFILE" in
  quick) PROFILE_LABEL="快速" ;;
  standard) PROFILE_LABEL="标准" ;;
  stress) PROFILE_LABEL="压力" ;;
  *) echo "PROFILE must be quick, standard, or stress" >&2; exit 2 ;;
esac

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB+=( -s "$SERIAL" )
fi

mkdir -p "$OUTPUT_DIR"

capture_state() {
  local suffix="$1"
  "${ADB[@]}" shell dumpsys battery > "$OUTPUT_DIR/battery-$suffix.txt" || true
  "${ADB[@]}" shell dumpsys thermalservice > "$OUTPUT_DIR/thermal-$suffix.txt" || true
  "${ADB[@]}" shell dumpsys meminfo "$PACKAGE" > "$OUTPUT_DIR/memory-$suffix.txt" || true
  "${ADB[@]}" shell df -k /data /sdcard > "$OUTPUT_DIR/storage-$suffix.txt" || true
  curl -fsS -H 'Authorization: Bearer local' "http://127.0.0.1:$HOST_PORT/metrics" \
    > "$OUTPUT_DIR/metrics-$suffix.json" || true
}

dump_ui() {
  local name="$1"
  "${ADB[@]}" shell uiautomator dump "/sdcard/$name.xml" >/dev/null
  "${ADB[@]}" pull "/sdcard/$name.xml" "$OUTPUT_DIR/$name.xml" >/dev/null
}

tap_text() {
  local label="$1"
  local xml="$2"
  local point
  point="$(LABEL="$label" perl -0777 -ne '
    $label = $ENV{"LABEL"};
    if (/text="\Q$label\E"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/) {
      print int(($1 + $3) / 2), " ", int(($2 + $4) / 2);
    }
  ' "$xml")"
  if [[ -z "$point" ]]; then
    echo "Unable to find visible UI text: $label" >&2
    return 1
  fi
  read -r x y <<<"$point"
  "${ADB[@]}" shell input tap "$x" "$y"
}

"${ADB[@]}" wait-for-device
DEVICE_SERIAL="$("${ADB[@]}" get-serialno)"
DEVICE_PRODUCT="$("${ADB[@]}" shell getprop ro.build.product | tr -d '\r')"
DEVICE_MODEL="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
DEVICE_ABI="$("${ADB[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"
IS_EMULATOR="$("${ADB[@]}" shell getprop ro.kernel.qemu | tr -d '\r')"

if [[ "$IS_EMULATOR" == "1" && "$ALLOW_EMULATOR" != "true" ]]; then
  echo "Refusing to certify an emulator. Set ALLOW_EMULATOR=true for a non-release smoke run." >&2
  exit 3
fi

if [[ "$DEVICE_ABI" != "arm64-v8a" ]]; then
  echo "TuiMa release validation requires arm64-v8a; found $DEVICE_ABI" >&2
  exit 4
fi

"${ADB[@]}" shell pm path "$PACKAGE" > "$OUTPUT_DIR/package-path.txt"
"${ADB[@]}" shell dumpsys package "$PACKAGE" > "$OUTPUT_DIR/package.txt"
"${ADB[@]}" shell appops get "$PACKAGE" > "$OUTPUT_DIR/appops.txt" || true
"${ADB[@]}" shell getprop > "$OUTPUT_DIR/device-properties.txt"
"${ADB[@]}" forward --remove "tcp:$HOST_PORT" >/dev/null 2>&1 || true
"${ADB[@]}" forward "tcp:$HOST_PORT" tcp:8080 >/dev/null

"${ADB[@]}" shell am force-stop "$PACKAGE"
"${ADB[@]}" shell am start -W -n "$PACKAGE/$ACTIVITY" > "$OUTPUT_DIR/activity-start.txt"

capture_state before
sleep 2
dump_ui benchmark-home
tap_text "检测" "$OUTPUT_DIR/benchmark-home.xml"
sleep 2
dump_ui benchmark-screen
START_MILLIS="$(python3 -c 'import time; print(int(time.time() * 1000))')"
tap_text "$PROFILE_LABEL" "$OUTPUT_DIR/benchmark-screen.xml"

START_SECONDS="$(date +%s)"
NEW_RUN_ID=""
while (( $(date +%s) - START_SECONDS < TIMEOUT_SECONDS )); do
  if curl -fsS -H 'Authorization: Bearer local' "http://127.0.0.1:$HOST_PORT/v1/benchmark/latest" \
      > "$OUTPUT_DIR/benchmark-latest.tmp.json" 2>/dev/null; then
    NEW_RUN_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("run_id", ""))' \
      < "$OUTPUT_DIR/benchmark-latest.tmp.json" 2>/dev/null || true)"
    CREATED_AT="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("created_at_ms", 0))' \
      < "$OUTPUT_DIR/benchmark-latest.tmp.json" 2>/dev/null || echo 0)"
    if [[ -n "$NEW_RUN_ID" && "$CREATED_AT" -ge "$START_MILLIS" ]]; then
      mv "$OUTPUT_DIR/benchmark-latest.tmp.json" "$OUTPUT_DIR/benchmark-latest.json"
      break
    fi
  fi
  sleep 5
done

if [[ -z "$NEW_RUN_ID" ]]; then
  echo "Benchmark did not produce a new report within $TIMEOUT_SECONDS seconds" >&2
  capture_state timeout
  "${ADB[@]}" logcat -d -t 4000 > "$OUTPUT_DIR/logcat.txt" || true
  exit 5
fi

capture_state after
curl -fsS "http://127.0.0.1:$HOST_PORT/health" > "$OUTPUT_DIR/health.json"
curl -fsS -H 'Authorization: Bearer local' "http://127.0.0.1:$HOST_PORT/v1/benchmark/reports?limit=5" \
  > "$OUTPUT_DIR/benchmark-reports.json"
"${ADB[@]}" exec-out screencap -p > "$OUTPUT_DIR/benchmark-result.png" || true
"${ADB[@]}" logcat -d -t 4000 > "$OUTPUT_DIR/logcat.txt" || true

SCORE="$(python3 -c 'import json,sys; d=json.load(sys.stdin); print((d.get("score") or {}).get("headline", "invalid"))' \
  < "$OUTPUT_DIR/benchmark-latest.json")"
FAILURE="$(python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("failure_kind") or "none")' \
  < "$OUTPUT_DIR/benchmark-latest.json")"

cat > "$OUTPUT_DIR/summary.txt" <<SUMMARY
TuiMa Android device acceptance
device_serial=$DEVICE_SERIAL
device_model=$DEVICE_MODEL
device_product=$DEVICE_PRODUCT
device_abi=$DEVICE_ABI
emulator=$IS_EMULATOR
profile=$PROFILE
run_id=$NEW_RUN_ID
headline_score=$SCORE
failure_kind=$FAILURE
output_dir=$OUTPUT_DIR
SUMMARY

cat "$OUTPUT_DIR/summary.txt"
[[ "$FAILURE" == "none" && "$SCORE" != "invalid" ]]
