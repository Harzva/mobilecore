#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080/v1}"
MODEL="${MODEL:-local-model}"

echo "== MobileCore P0 API Test =="
echo "Base URL: ${BASE_URL}"
echo "Model: ${MODEL}"
echo

echo "== GET /models =="
curl -sS "${BASE_URL}/models" | python -m json.tool || true

echo
echo "== POST /chat/completions =="
curl -sS "${BASE_URL}/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer local" \
  -d "{\"model\":\"${MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"用一句话介绍 MobileCore。\"}],\"max_tokens\":128,\"temperature\":0.7,\"stream\":false}" \
  | python -m json.tool || true

echo
echo "API test finished."
