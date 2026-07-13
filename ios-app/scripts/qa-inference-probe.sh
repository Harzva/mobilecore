#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$IOS_DIR/.." && pwd)"
BUNDLE_ID="${TUIMA_IOS_BUNDLE_ID:-ai.mobilecore.ios}"
SIMULATOR_ID="${TUIMA_IOS_SIMULATOR_ID:-}"
MODEL_PATH="${TUIMA_IOS_MODEL_PATH:-$REPO_DIR/android-app/.model-cache/modelscope/Qwen/Qwen2.5-0.5B-Instruct-GGUF/qwen2.5-0.5b-instruct-q4_k_m.gguf}"
EXPECTED_MODEL_SHA256="${TUIMA_IOS_MODEL_SHA256:-74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db}"
MARKER="${TUIMA_IOS_PROBE_MARKER:-TUIMA_IOS_OK}"
DERIVED_DATA="$IOS_DIR/build/DerivedData"
QA_DIR="$IOS_DIR/build/qa"

if [[ ! -f "$MODEL_PATH" ]]; then
  echo "GGUF model not found: $MODEL_PATH" >&2
  exit 2
fi

actual_sha256="$(shasum -a 256 "$MODEL_PATH" | awk '{print $1}')"
if [[ "$actual_sha256" != "$EXPECTED_MODEL_SHA256" ]]; then
  echo "GGUF SHA-256 mismatch: expected $EXPECTED_MODEL_SHA256, got $actual_sha256" >&2
  exit 3
fi

if [[ -z "$SIMULATOR_ID" ]]; then
  SIMULATOR_ID="$(xcrun simctl list devices available -j | /usr/bin/python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"]
for runtime in sorted(devices, reverse=True):
    for device in devices[runtime]:
        if device.get("isAvailable") and device.get("deviceTypeIdentifier", "").startswith("com.apple.CoreSimulator.SimDeviceType.iPhone"):
            print(device["udid"])
            raise SystemExit(0)
raise SystemExit(1)
')"
fi

xcrun simctl boot "$SIMULATOR_ID" 2>/dev/null || true
xcrun simctl bootstatus "$SIMULATOR_ID" -b

xcodebuild \
  -project "$IOS_DIR/MobileCoreiOS.xcodeproj" \
  -scheme MobileCoreiOS \
  -configuration Debug \
  -destination "platform=iOS Simulator,id=$SIMULATOR_ID" \
  -derivedDataPath "$DERIVED_DATA" \
  CODE_SIGNING_ALLOWED=NO \
  build >/dev/null

APP_PATH="$DERIVED_DATA/Build/Products/Debug-iphonesimulator/MobileCoreiOS.app"
xcrun simctl install "$SIMULATOR_ID" "$APP_PATH"

DATA_CONTAINER="$(xcrun simctl get_app_container "$SIMULATOR_ID" "$BUNDLE_ID" data)"
MODEL_DIR="$DATA_CONTAINER/Documents/MobileCore/models"
REPORT_PATH="$DATA_CONTAINER/Documents/MobileCore/qa/ios-inference-probe.json"
mkdir -p "$MODEL_DIR" "$(dirname "$REPORT_PATH")" "$QA_DIR"
rm -f "$REPORT_PATH"
cp "$MODEL_PATH" "$MODEL_DIR/$(basename "$MODEL_PATH")"

xcrun simctl launch --terminate-running-process "$SIMULATOR_ID" "$BUNDLE_ID" \
  --tuima-inference-probe \
  --tuima-probe-marker "$MARKER" \
  --tuima-probe-max-tokens 24 >/dev/null

for _ in $(seq 1 180); do
  if [[ -s "$REPORT_PATH" ]]; then
    break
  fi
  sleep 1
done

if [[ ! -s "$REPORT_PATH" ]]; then
  echo "Timed out waiting for iOS inference probe report." >&2
  exit 4
fi

cp "$REPORT_PATH" "$QA_DIR/ios-inference-probe.json"
/usr/bin/python3 - "$QA_DIR/ios-inference-probe.json" "$actual_sha256" <<'PY'
import json, pathlib, sys

path = pathlib.Path(sys.argv[1])
report = json.loads(path.read_text())
report["model_sha256"] = sys.argv[2]
path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
print(json.dumps(report, indent=2, sort_keys=True))
if not report.get("valid"):
    raise SystemExit(5)
PY
