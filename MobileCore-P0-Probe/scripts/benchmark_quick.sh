#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080/v1}"
MODEL="${MODEL:-local-model}"
OUT_DIR="${HOME}/mobilecore/logs"
mkdir -p "${OUT_DIR}"
OUT_FILE="${OUT_DIR}/p0-benchmark-quick.jsonl"

PROMPT="请用三点概括 MobileCore 的作用，每点不超过二十个字。"
START_TS="$(date +%s)"
START_MS="$(date +%s%3N 2>/dev/null || date +%s000)"

RESP_FILE="$(mktemp)"

curl -sS "${BASE_URL}/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer local" \
  -d "{\"model\":\"${MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"${PROMPT}\"}],\"max_tokens\":256,\"temperature\":0.7,\"stream\":false}" \
  > "${RESP_FILE}"

END_MS="$(date +%s%3N 2>/dev/null || date +%s000)"
ELAPSED_MS="$((END_MS - START_MS))"

CONTENT_CHARS="$(python - <<'PY' "${RESP_FILE}"
import json, sys
p=sys.argv[1]
try:
    data=json.load(open(p, 'r', encoding='utf-8'))
    text=data.get('choices',[{}])[0].get('message',{}).get('content','')
    print(len(text))
except Exception:
    print(0)
PY
)"

cat "${RESP_FILE}"
echo

echo "{\"ts\":${START_TS},\"model\":\"${MODEL}\",\"elapsed_ms\":${ELAPSED_MS},\"content_chars\":${CONTENT_CHARS}}" >> "${OUT_FILE}"
echo "Benchmark record appended to: ${OUT_FILE}"
rm -f "${RESP_FILE}"
