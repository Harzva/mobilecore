#!/usr/bin/env bash
set -euo pipefail

MODEL_PATH="${1:-}"
PORT="${PORT:-8080}"
HOST="${HOST:-127.0.0.1}"
CTX="${CTX:-2048}"

if [ -z "${MODEL_PATH}" ]; then
  echo "Usage: $0 /path/to/model.gguf"
  echo "Optional env: PORT=8080 HOST=127.0.0.1 CTX=2048"
  exit 1
fi

if [ ! -f "${MODEL_PATH}" ]; then
  echo "Model file not found: ${MODEL_PATH}"
  exit 1
fi

if command -v llama-server >/dev/null 2>&1; then
  LLAMA_SERVER="$(command -v llama-server)"
elif [ -x "${HOME}/llama.cpp/build/bin/llama-server" ]; then
  LLAMA_SERVER="${HOME}/llama.cpp/build/bin/llama-server"
else
  echo "llama-server not found. Install llama.cpp first."
  exit 1
fi

echo "Starting MobileCore P0 llama-server"
echo "Binary: ${LLAMA_SERVER}"
echo "Model: ${MODEL_PATH}"
echo "Host: ${HOST}"
echo "Port: ${PORT}"
echo "Context: ${CTX}"

exec "${LLAMA_SERVER}" \
  -m "${MODEL_PATH}" \
  --host "${HOST}" \
  --port "${PORT}" \
  -c "${CTX}"
