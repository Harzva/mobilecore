#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${HOME}/mobilecore"
LOG_DIR="${ROOT_DIR}/logs"
MODEL_DIR="${ROOT_DIR}/models"
mkdir -p "${LOG_DIR}" "${MODEL_DIR}"

REPORT="${LOG_DIR}/p0-env-check.txt"
: > "${REPORT}"

echo "== MobileCore P0 Environment Check ==" | tee -a "${REPORT}"
echo "Root: ${ROOT_DIR}" | tee -a "${REPORT}"
echo | tee -a "${REPORT}"

run_check() {
  echo "--- $1 ---" | tee -a "${REPORT}"
  shift
  ( "$@" 2>&1 || true ) | tee -a "${REPORT}"
  echo | tee -a "${REPORT}"
}

run_check "uname" uname -a
run_check "android brand" getprop ro.product.brand
run_check "android model" getprop ro.product.model
run_check "android version" getprop ro.build.version.release
run_check "cpu abi" getprop ro.product.cpu.abi
run_check "memory" free -h
run_check "storage" df -h
run_check "node" node -v
run_check "python" python --version
run_check "curl" curl --version
run_check "llama-server path" which llama-server
run_check "llama-cli path" which llama-cli

echo "Report written to: ${REPORT}"
echo "Model directory: ${MODEL_DIR}"
