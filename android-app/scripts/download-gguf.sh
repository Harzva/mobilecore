#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CATALOG="${MOBILECORE_MODEL_CATALOG:-$APP_DIR/model-downloads/catalog.tsv}"
CACHE_DIR="${MOBILECORE_MODEL_CACHE:-$APP_DIR/.model-cache}"
PROVIDER="${MOBILECORE_MODEL_PROVIDER:-hf}"
ALIAS="${MOBILECORE_MODEL_ALIAS:-qwen2.5-0.5b-q4km}"
REPO=""
FILE=""
PARAMS_B=""
QUANT=""
SIZE_MB=""
TIER=""
TAGS=""
PUSH_TO_DEVICE=0
LOAD_AFTER_PUSH=0
INSTALL_TOOLS=0
LIST_ONLY=0
DRY_RUN=0
BATCH_DOWNLOAD=0
YES=0
MAX_PARAMS_B=""
TIER_FILTER=""
CONTEXT_LENGTH="${MOBILECORE_CONTEXT_LENGTH:-2048}"
ADB_SERIAL="${MOBILECORE_ADB_SERIAL:-}"
DEVICE_PUSH_DIR="${MOBILECORE_DEVICE_MODEL_DIR:-/sdcard/Android/data/com.mobilecore.app/files/models}"
DEVICE_LOAD_DIR="${MOBILECORE_DEVICE_LOAD_DIR:-/storage/emulated/0/Android/data/com.mobilecore.app/files/models}"

usage() {
  cat <<'EOF'
Usage:
  scripts/download-gguf.sh [alias] [options]

Options:
  --provider hf|modelscope      Download provider. Default: hf
  --alias NAME                  Catalog alias. Default: qwen2.5-0.5b-q4km
  --repo REPO --file FILE       Override catalog with an explicit repo/file.
  --cache DIR                   Local model cache. Default: .model-cache
  --all-modelscope              Download every ModelScope entry selected by filters.
  --tier NAME                   Filter batch downloads by tier: tiny, phone, tablet, heavy, recommended.
  --max-params-b N              Filter batch downloads to models <= N billion params.
  --yes                         Confirm a batch download. Without this, batch mode is dry-plan only.
  --push                        Push downloaded GGUF to the installed app's models dir.
  --load                        After --push, call /mobilecore/model/load through adb forward.
  --context-length N            Context length for --load. Default: 2048
  --serial SERIAL               adb serial when multiple devices are connected.
  --install-tools               Create .tools/model-downloaders and install HF/ModelScope CLIs.
  --list                        Print catalog entries.
  --dry-run                     Resolve alias/provider and print the planned target only.
  -h, --help                    Show help.

Examples:
  scripts/download-gguf.sh --provider hf --alias smollm2-135m-q4km
  scripts/download-gguf.sh --provider modelscope --alias qwen2.5-0.5b-q4km
  scripts/download-gguf.sh --provider modelscope --alias qwen2.5-0.5b-q4km --push --load
  scripts/download-gguf.sh --provider modelscope --tier recommended --max-params-b 4 --dry-run
  scripts/download-gguf.sh --all-modelscope --tier tiny --yes
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

adb_cmd() {
  if [[ -n "$ADB_SERIAL" ]]; then
    adb -s "$ADB_SERIAL" "$@"
  else
    adb "$@"
  fi
}

list_catalog() {
  awk -F '\t' '
    BEGIN {
      printf "%-30s %-10s %7s %-10s %8s %-18s %s\n", "alias", "provider", "params", "quant", "size", "tier", "target"
    }
    NF >= 4 && $1 !~ /^#/ {
      params = ($5 == "" ? "-" : $5 "B")
      quant = ($6 == "" ? "-" : $6)
      size = ($7 == "" ? "-" : $7 "MB")
      tier = ($8 == "" ? "-" : $8)
      printf "%-30s %-10s %7s %-10s %8s %-18s %s/%s\n", $1, $2, params, quant, size, tier, $3, $4
    }
  ' "$CATALOG"
}

resolve_catalog() {
  [[ -f "$CATALOG" ]] || die "catalog not found: $CATALOG"
  local line
  line="$(awk -F '\t' -v alias="$ALIAS" -v provider="$PROVIDER" 'NF >= 4 && $1 == alias && $2 == provider { print; exit }' "$CATALOG")"
  [[ -n "$line" ]] || die "no catalog entry for alias=$ALIAS provider=$PROVIDER. Run --list."
  IFS=$'\t' read -r _ _ REPO FILE PARAMS_B QUANT SIZE_MB TIER TAGS <<<"$line"
}

selected_catalog_aliases() {
  [[ -f "$CATALOG" ]] || die "catalog not found: $CATALOG"
  awk -F '\t' -v provider="$PROVIDER" -v max_params="$MAX_PARAMS_B" -v tier="$TIER_FILTER" '
    NF >= 4 && $1 !~ /^#/ && $2 == provider {
      params = ($5 == "" ? 9999 : $5 + 0)
      if (max_params != "" && params > max_params + 0) next
      if (tier != "" && ("," $8 ",") !~ ("," tier ",")) next
      print $1
    }
  ' "$CATALOG"
}

print_selection_plan() {
  local aliases=("$@")
  if [[ "${#aliases[@]}" -eq 0 ]]; then
    die "no catalog entries matched provider=$PROVIDER tier=${TIER_FILTER:-any} max_params=${MAX_PARAMS_B:-any}"
  fi
  printf 'Selected %s catalog entries:\n' "${#aliases[@]}"
  local total_mb=0
  local alias
  for alias in "${aliases[@]}"; do
    ALIAS="$alias"
    resolve_catalog
    total_mb=$((total_mb + ${SIZE_MB:-0}))
    printf '  %-30s %5sB %-10s %6sMB %s/%s\n' "$ALIAS" "${PARAMS_B:-?}" "${QUANT:-?}" "${SIZE_MB:-0}" "$REPO" "$FILE"
  done
  printf 'Estimated download size: %.2f GB\n' "$(awk -v mb="$total_mb" 'BEGIN { print mb / 1024 }')"
}

install_tools() {
  command -v python3 >/dev/null 2>&1 || die "python3 is required for --install-tools"
  local venv="$APP_DIR/.tools/model-downloaders"
  python3 -m venv "$venv"
  "$venv/bin/python" -m pip install --upgrade pip
  "$venv/bin/python" -m pip install --upgrade "huggingface_hub[cli]" modelscope
  printf 'Installed model download tools in %s\n' "$venv"
  printf 'Activate with: source %s/bin/activate\n' "$venv"
}

download_with_hf() {
  local target_dir="$1"
  mkdir -p "$target_dir"
  if is_valid_gguf "$target_dir/$FILE"; then
    printf 'Using cached GGUF: %s\n' "$target_dir/$FILE"
    return
  fi
  if command -v hf >/dev/null 2>&1; then
    hf download "$REPO" "$FILE" --local-dir "$target_dir"
  elif command -v huggingface-cli >/dev/null 2>&1; then
    huggingface-cli download "$REPO" "$FILE" --local-dir "$target_dir"
  else
    local url="https://huggingface.co/$REPO/resolve/main/$FILE?download=true"
    curl -L --fail --retry 5 --retry-delay 3 --retry-all-errors -o "$target_dir/$FILE" "$url"
  fi
}

download_with_modelscope() {
  local target_dir="$1"
  mkdir -p "$target_dir"
  if is_valid_gguf "$target_dir/$FILE"; then
    printf 'Using cached GGUF: %s\n' "$target_dir/$FILE"
    return
  fi
  if command -v modelscope >/dev/null 2>&1; then
    modelscope download --model "$REPO" "$FILE" --local_dir "$target_dir"
  elif python3 - "$REPO" "$FILE" "$target_dir" <<'PY'
import importlib.util
import sys

sys.exit(0 if importlib.util.find_spec("modelscope") else 1)
PY
  then
    python3 - "$REPO" "$FILE" "$target_dir" <<'PY'
import sys
from modelscope import snapshot_download

repo, filename, target_dir = sys.argv[1:4]
snapshot_download(repo, local_dir=target_dir, allow_patterns=[filename])
PY
  else
    local url="https://modelscope.cn/models/$REPO/resolve/master/$FILE"
    curl -L --fail --retry 5 --retry-delay 3 --retry-all-errors -o "$target_dir/$FILE" "$url"
  fi
}

is_valid_gguf() {
  local file_path="$1"
  [[ -s "$file_path" ]] || return 1
  [[ "$(LC_ALL=C dd if="$file_path" bs=4 count=1 2>/dev/null)" == "GGUF" ]]
}

find_downloaded_file() {
  local target_dir="$1"
  if [[ -f "$target_dir/$FILE" ]]; then
    printf '%s\n' "$target_dir/$FILE"
    return
  fi
  local found
  found="$(find "$target_dir" -type f -name "$FILE" -print | head -n 1 || true)"
  [[ -n "$found" ]] || die "download finished but file was not found: $FILE"
  printf '%s\n' "$found"
}

push_to_device() {
  local model_path="$1"
  command -v adb >/dev/null 2>&1 || die "adb is required for --push"
  adb_cmd shell mkdir -p "$DEVICE_PUSH_DIR"
  adb_cmd push "$model_path" "$DEVICE_PUSH_DIR/"
  printf '%s/%s\n' "$DEVICE_LOAD_DIR" "$(basename "$model_path")"
}

load_on_device() {
  local device_model_path="$1"
  command -v curl >/dev/null 2>&1 || die "curl is required for --load"
  adb_cmd forward tcp:8080 tcp:8080 >/dev/null
  curl -sS -X POST http://127.0.0.1:8080/mobilecore/model/load \
    -H 'Authorization: Bearer local' \
    -H 'Content-Type: application/json' \
    -d "{\"path\":\"$device_model_path\",\"context_length\":$CONTEXT_LENGTH}"
  printf '\n'
}

download_current_alias() {
  if [[ -z "$REPO" || -z "$FILE" ]]; then
    resolve_catalog
  fi
  [[ -n "$REPO" && -n "$FILE" ]] || die "repo and file are required"

  local target_dir="$CACHE_DIR/$PROVIDER/$REPO"
  printf 'Provider: %s\nRepo: %s\nFile: %s\nCache: %s\n' "$PROVIDER" "$REPO" "$FILE" "$target_dir"

  if [[ "$DRY_RUN" == 1 ]]; then
    return
  fi

  case "$PROVIDER" in
    hf) download_with_hf "$target_dir" ;;
    modelscope) download_with_modelscope "$target_dir" ;;
  esac

  local downloaded
  downloaded="$(find_downloaded_file "$target_dir")"
  printf 'Downloaded: %s\n' "$downloaded"

  if [[ "$PUSH_TO_DEVICE" == 1 ]]; then
    local device_path
    device_path="$(push_to_device "$downloaded")"
    printf 'Device path: %s\n' "$device_path"
    if [[ "$LOAD_AFTER_PUSH" == 1 ]]; then
      load_on_device "$device_path"
    fi
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --provider)
      PROVIDER="${2:-}"
      shift 2
      ;;
    --alias)
      ALIAS="${2:-}"
      shift 2
      ;;
    --repo)
      REPO="${2:-}"
      shift 2
      ;;
    --file)
      FILE="${2:-}"
      shift 2
      ;;
    --cache)
      CACHE_DIR="${2:-}"
      shift 2
      ;;
    --all-modelscope)
      PROVIDER="modelscope"
      BATCH_DOWNLOAD=1
      shift
      ;;
    --tier)
      TIER_FILTER="${2:-}"
      BATCH_DOWNLOAD=1
      shift 2
      ;;
    --max-params-b)
      MAX_PARAMS_B="${2:-}"
      BATCH_DOWNLOAD=1
      shift 2
      ;;
    --yes)
      YES=1
      shift
      ;;
    --push)
      PUSH_TO_DEVICE=1
      shift
      ;;
    --load)
      PUSH_TO_DEVICE=1
      LOAD_AFTER_PUSH=1
      shift
      ;;
    --context-length)
      CONTEXT_LENGTH="${2:-}"
      shift 2
      ;;
    --serial)
      ADB_SERIAL="${2:-}"
      shift 2
      ;;
    --install-tools)
      INSTALL_TOOLS=1
      shift
      ;;
    --list)
      LIST_ONLY=1
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      die "unknown option: $1"
      ;;
    *)
      ALIAS="$1"
      shift
      ;;
  esac
done

if [[ "$INSTALL_TOOLS" == 1 ]]; then
  install_tools
  exit 0
fi

if [[ "$LIST_ONLY" == 1 ]]; then
  list_catalog
  exit 0
fi

[[ "$PROVIDER" == "hf" || "$PROVIDER" == "modelscope" ]] || die "provider must be hf or modelscope"

if [[ "$BATCH_DOWNLOAD" == 1 ]]; then
  selected_aliases=()
  while IFS= read -r selected_alias; do
    [[ -n "$selected_alias" ]] && selected_aliases+=("$selected_alias")
  done < <(selected_catalog_aliases)
  print_selection_plan "${selected_aliases[@]}"
  if [[ "$DRY_RUN" == 1 ]]; then
    exit 0
  fi
  [[ "$YES" == 1 ]] || die "batch download requires --yes. Re-run with --dry-run first to review size."
  for selected_alias in "${selected_aliases[@]}"; do
    printf '\n==> %s\n' "$selected_alias"
    ALIAS="$selected_alias"
    download_current_alias
  done
  exit 0
fi

if [[ -z "$REPO" || -z "$FILE" ]]; then
  resolve_catalog
fi
download_current_alias
