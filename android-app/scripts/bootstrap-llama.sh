#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LLAMA_DIR="${MOBILECORE_LLAMA_CPP_DIR:-$APP_DIR/third_party/llama.cpp}"
LLAMA_REPO="${MOBILECORE_LLAMA_CPP_REPO:-https://github.com/ggml-org/llama.cpp.git}"
LLAMA_REF="${MOBILECORE_LLAMA_CPP_REF:-063d9c156e816ae3cf62db01f429a07a099afe97}"

if [[ -d "$LLAMA_DIR/.git" ]]; then
  echo "Using existing llama.cpp checkout: $LLAMA_DIR"
else
  mkdir -p "$(dirname "$LLAMA_DIR")"
  git clone --filter=blob:none "$LLAMA_REPO" "$LLAMA_DIR"
fi

git -C "$LLAMA_DIR" fetch --depth 1 origin "$LLAMA_REF"
git -C "$LLAMA_DIR" checkout --detach FETCH_HEAD

echo "llama.cpp is ready at $LLAMA_DIR"
git -C "$LLAMA_DIR" rev-parse --short HEAD
