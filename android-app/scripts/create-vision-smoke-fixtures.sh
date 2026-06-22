#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV_DIR="$ROOT_DIR/.tools/vision-fixtures"
OUT_DIR="${1:-$ROOT_DIR/model-downloads/vision-fixtures}"

python3 -m venv "$VENV_DIR"
# shellcheck source=/dev/null
source "$VENV_DIR/bin/activate"
python -m pip install --upgrade pip >/dev/null
python -m pip install "onnx==1.16.2" "numpy<3" >/dev/null

mkdir -p "$OUT_DIR"
python "$ROOT_DIR/scripts/create_clip_cifar10_fixture.py" "$OUT_DIR"

echo "Wrote vision smoke fixtures to $OUT_DIR"
ls -lh "$OUT_DIR"
