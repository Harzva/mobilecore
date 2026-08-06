#!/usr/bin/env bash
set -euo pipefail

CORE_PACKAGE="${CORE_PACKAGE:-com.mobilecore.app}"
CLIENT_PACKAGE="${CLIENT_PACKAGE:-com.mobilecode.app}"
CLIENT_ACTIVITY="${CLIENT_ACTIVITY:-com.mobilecode.app.MainActivity}"
HOST_PORT="${HOST_PORT:-18082}"
POLL_COUNT="${POLL_COUNT:-12}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-3}"
OUTPUT_ROOT="${OUTPUT_ROOT:-artifacts/mobilecode-background-link}"
SERIAL="${ANDROID_SERIAL:-}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUTPUT_DIR="$OUTPUT_ROOT/$STAMP"

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB+=( -s "$SERIAL" )
fi

mkdir -p "$OUTPUT_DIR"
"${ADB[@]}" wait-for-device

for package in "$CORE_PACKAGE" "$CLIENT_PACKAGE"; do
  if ! "${ADB[@]}" shell pm path "$package" > "$OUTPUT_DIR/$package-path.txt"; then
    echo "Required package is not installed: $package" >&2
    exit 2
  fi
done

"${ADB[@]}" forward --remove "tcp:$HOST_PORT" >/dev/null 2>&1 || true
"${ADB[@]}" forward "tcp:$HOST_PORT" tcp:8080 >/dev/null

if ! curl -fsS --max-time 5 "http://127.0.0.1:$HOST_PORT/health" > "$OUTPUT_DIR/health-before.json"; then
  echo "MobileCore must already be running and healthy before this check." >&2
  exit 3
fi

"${ADB[@]}" shell dumpsys activity services "$CORE_PACKAGE" > "$OUTPUT_DIR/service-before.txt"
LOGCAT_START="$("${ADB[@]}" shell date '+%m-%d %H:%M:%S.000' | tr -d '\r')"
"${ADB[@]}" shell am start -W -n "$CLIENT_PACKAGE/$CLIENT_ACTIVITY" > "$OUTPUT_DIR/mobilecode-start.txt"

failed_polls=0
for ((poll = 1; poll <= POLL_COUNT; poll += 1)); do
  if ! curl -fsS --max-time 5 "http://127.0.0.1:$HOST_PORT/health" \
      > "$OUTPUT_DIR/health-background-$poll.json"; then
    failed_polls=$((failed_polls + 1))
  fi
  sleep "$POLL_INTERVAL_SECONDS"
done

"${ADB[@]}" shell dumpsys activity services "$CORE_PACKAGE" > "$OUTPUT_DIR/service-background.txt"
"${ADB[@]}" shell dumpsys activity processes "$CORE_PACKAGE" > "$OUTPUT_DIR/process-background.txt"
"${ADB[@]}" shell dumpsys deviceidle whitelist > "$OUTPUT_DIR/deviceidle-whitelist.txt" || true
"${ADB[@]}" logcat -d -T "$LOGCAT_START" > "$OUTPUT_DIR/logcat.txt" || true
"${ADB[@]}" exec-out screencap -p > "$OUTPUT_DIR/mobilecode-foreground.png" || true

foreground=false
if grep -q "isForeground=true" "$OUTPUT_DIR/service-background.txt"; then
  foreground=true
fi

frozen=false
if grep -Eq "${CORE_PACKAGE}.*(FROZEN|frozen=true)|freez(e|ing).*${CORE_PACKAGE}" \
    "$OUTPUT_DIR/process-background.txt" "$OUTPUT_DIR/logcat.txt"; then
  frozen=true
fi

printf '%s\n' \
  "MobileCore -> MobileCode background-link acceptance" \
  "core_package=$CORE_PACKAGE" \
  "client_package=$CLIENT_PACKAGE" \
  "poll_count=$POLL_COUNT" \
  "failed_polls=$failed_polls" \
  "service_foreground=$foreground" \
  "process_frozen=$frozen" \
  "output_dir=$OUTPUT_DIR" \
  > "$OUTPUT_DIR/summary.txt"

cat "$OUTPUT_DIR/summary.txt"

if [[ "$failed_polls" -ne 0 || "$foreground" != true || "$frozen" == true ]]; then
  exit 4
fi
