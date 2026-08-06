#!/usr/bin/env bash
set -euo pipefail

CORE_PACKAGE="${CORE_PACKAGE:-com.mobilecore.app}"
CLIENT_PACKAGE="${CLIENT_PACKAGE:-com.mobilecode.app}"
CLIENT_COMPONENT="${CLIENT_COMPONENT:-com.mobilecode.app/.MainActivity}"
EXPECTED_CORE_VERSION="${EXPECTED_CORE_VERSION:-0.1.4-rc6}"
HOST_PORT="${HOST_PORT:-18082}"
POLL_COUNT="${POLL_COUNT:-40}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-3}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_ROOT="${OUTPUT_ROOT:-$ANDROID_APP_DIR/qa-output/mobilecode-background-link}"
SERIAL="${ANDROID_SERIAL:-}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUTPUT_DIR="$OUTPUT_ROOT/$STAMP"

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB+=( -s "$SERIAL" )
fi

mkdir -p "$OUTPUT_DIR"
"${ADB[@]}" wait-for-device

cleanup() {
  "${ADB[@]}" forward --remove "tcp:$HOST_PORT" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for package in "$CORE_PACKAGE" "$CLIENT_PACKAGE"; do
  if ! "${ADB[@]}" shell pm path "$package" >/dev/null; then
    echo "Required package is not installed: $package" >&2
    exit 2
  fi
done

"${ADB[@]}" forward --remove "tcp:$HOST_PORT" >/dev/null 2>&1 || true
"${ADB[@]}" forward "tcp:$HOST_PORT" tcp:8080 >/dev/null

AUTH_HEADERS=(
  -H "Authorization: Bearer local"
  -H "X-MobileCore-Client: mobilecore-background-link-qa"
)

if ! curl -fsS --max-time 5 "${AUTH_HEADERS[@]}" \
    "http://127.0.0.1:$HOST_PORT/health" > "$OUTPUT_DIR/health-before.json"; then
  echo "MobileCore must already be running and healthy before this check." >&2
  exit 3
fi
actual_core_version="$(
  python3 -c 'import json,sys; print(json.load(sys.stdin).get("version", ""))' \
    < "$OUTPUT_DIR/health-before.json"
)"
if [[ "$actual_core_version" != "$EXPECTED_CORE_VERSION" ]]; then
  echo "Expected MobileCore $EXPECTED_CORE_VERSION, found $actual_core_version" >&2
  exit 3
fi

core_process_state_before="$("${ADB[@]}" shell dumpsys activity processes "$CORE_PACKAGE")"
if grep -q "backgroundRestricted=true" <<< "$core_process_state_before"; then
  echo "MobileCore is background-restricted; use the system Battery settings to allow background operation before acceptance." >&2
  exit 3
fi

LOGCAT_START="$("${ADB[@]}" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')"
"${ADB[@]}" shell am start -W -n "$CLIENT_COMPONENT" > "$OUTPUT_DIR/mobilecode-start.txt"

failed_polls=0
for ((poll = 1; poll <= POLL_COUNT; poll += 1)); do
  if ! curl -fsS --max-time 5 "${AUTH_HEADERS[@]}" \
      "http://127.0.0.1:$HOST_PORT/health" \
      > "$OUTPUT_DIR/health-background-$poll.json"; then
    failed_polls=$((failed_polls + 1))
  fi
  if [[ "$poll" -lt "$POLL_COUNT" ]]; then
    sleep "$POLL_INTERVAL_SECONDS"
  fi
done

service_state="$("${ADB[@]}" shell dumpsys activity services "$CORE_PACKAGE")"
process_state="$("${ADB[@]}" shell dumpsys activity processes "$CORE_PACKAGE")"
activity_state="$("${ADB[@]}" shell dumpsys activity activities)"
logcat_safety="$(
  "${ADB[@]}" logcat -d -T "$LOGCAT_START" 2>/dev/null \
    | grep -Ei "${CORE_PACKAGE}.*(FROZEN|frozen=true|OutOfMemoryError|SIGABRT)|freez(e|ing).*${CORE_PACKAGE}|Process: ${CORE_PACKAGE}|ANR in ${CORE_PACKAGE}|Stop FGS timeout: .*${CORE_PACKAGE}" \
    || true
)"
printf '%s\n' "$logcat_safety" > "$OUTPUT_DIR/logcat-safety.txt"

foreground=false
if grep -q "isForeground=true" <<< "$service_state"; then
  foreground=true
fi

frozen=false
if grep -Eq "${CORE_PACKAGE}.*(FROZEN|frozen=true)|freez(e|ing).*${CORE_PACKAGE}" \
    <<< "$process_state" || grep -Eq "${CORE_PACKAGE}.*(FROZEN|frozen=true)|freez(e|ing).*${CORE_PACKAGE}" <<< "$logcat_safety"; then
  frozen=true
fi

background_restricted=false
if grep -q "backgroundRestricted=true" <<< "$process_state"; then
  background_restricted=true
fi

safety_failure=false
if [[ -n "$logcat_safety" ]]; then
  safety_failure=true
fi

process_alive=false
if "${ADB[@]}" shell pidof "$CORE_PACKAGE" >/dev/null; then
  process_alive=true
fi

client_foreground=false
if grep -Eq "(mResumedActivity|topResumedActivity|ResumedActivity)[=:].*${CLIENT_PACKAGE}" <<< "$activity_state"; then
  client_foreground=true
fi

printf '%s\n' \
  "MobileCore -> MobileCode background-link acceptance" \
  "core_package=$CORE_PACKAGE" \
  "core_version=$actual_core_version" \
  "client_package=$CLIENT_PACKAGE" \
  "poll_count=$POLL_COUNT" \
  "failed_polls=$failed_polls" \
  "service_foreground=$foreground" \
  "process_alive=$process_alive" \
  "client_foreground=$client_foreground" \
  "core_background_restricted=$background_restricted" \
  "process_frozen=$frozen" \
  "safety_failure=$safety_failure" \
  "artifact_id=$STAMP" \
  "redaction=raw_dumpsys_and_full_logcat_omitted" \
  > "$OUTPUT_DIR/summary.txt"

cat "$OUTPUT_DIR/summary.txt"

if [[ "$failed_polls" -ne 0 || "$foreground" != true || "$process_alive" != true || "$client_foreground" != true || "$background_restricted" == true || "$frozen" == true || "$safety_failure" == true ]]; then
  exit 4
fi
