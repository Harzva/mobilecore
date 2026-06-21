#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$IOS_DIR/.." && pwd)"
LLAMA_DIR="${MOBILECORE_LLAMA_CPP_DIR:-$REPO_DIR/android-app/third_party/llama.cpp}"

if [[ ! -f "$LLAMA_DIR/CMakeLists.txt" ]]; then
  cat >&2 <<MSG
llama.cpp checkout was not found at:
  $LLAMA_DIR

Restore it first:
  cd "$REPO_DIR/android-app"
  ./scripts/bootstrap-llama.sh

Or set MOBILECORE_LLAMA_CPP_DIR to an existing llama.cpp checkout.
MSG
  exit 1
fi

find_tool() {
  local tool_name="$1"
  if command -v "$tool_name" >/dev/null 2>&1; then
    command -v "$tool_name"
    return 0
  fi

  local android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  local candidate
  candidate="$(find "$android_sdk/cmake" -maxdepth 3 -type f -name "$tool_name" 2>/dev/null | sort | tail -n 1 || true)"
  if [[ -n "$candidate" && -x "$candidate" ]]; then
    printf '%s\n' "$candidate"
    return 0
  fi

  return 1
}

CMAKE_BIN="${CMAKE_BIN:-$(find_tool cmake || true)}"
NINJA_BIN="${NINJA_BIN:-$(find_tool ninja || true)}"

if [[ -z "$CMAKE_BIN" ]]; then
  echo "CMake was not found. Install CMake or Android SDK CMake 3.22.1+." >&2
  exit 1
fi

PLATFORM="${PLATFORM_NAME:-iphonesimulator}"
CONFIG="${CONFIGURATION:-Debug}"

if [[ -z "${SDKROOT:-}" ]]; then
  if [[ "$PLATFORM" == "iphoneos" ]]; then
    SDKROOT="$(xcrun --sdk iphoneos --show-sdk-path)"
  else
    SDKROOT="$(xcrun --sdk iphonesimulator --show-sdk-path)"
  fi
fi

ARCH_LIST="${ARCHS:-}"
if [[ -z "$ARCH_LIST" ]]; then
  if [[ "$PLATFORM" == "iphoneos" ]]; then
    ARCH_LIST="arm64"
  else
    ARCH_LIST="arm64 x86_64"
  fi
fi

ARCHS_CMAKE="$(printf '%s' "$ARCH_LIST" | tr ' ' ';')"
BUILD_DIR="$IOS_DIR/build/llama-$PLATFORM-$CONFIG"
JOBS="${LLAMA_BUILD_JOBS:-$(sysctl -n hw.ncpu 2>/dev/null || printf '4')}"

generator_args=()
if [[ -n "$NINJA_BIN" ]]; then
  generator_args=(-G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA_BIN")
fi

"$CMAKE_BIN" \
  -S "$LLAMA_DIR" \
  -B "$BUILD_DIR" \
  "${generator_args[@]}" \
  -DCMAKE_SYSTEM_NAME=iOS \
  -DCMAKE_OSX_SYSROOT="$SDKROOT" \
  -DCMAKE_OSX_ARCHITECTURES="$ARCHS_CMAKE" \
  -DCMAKE_OSX_DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-17.0}" \
  -DCMAKE_BUILD_TYPE="$CONFIG" \
  -DBUILD_SHARED_LIBS=OFF \
  -DLLAMA_BUILD_COMMON=OFF \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_TOOLS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_BUILD_APP=OFF \
  -DLLAMA_BUILD_UI=OFF \
  -DLLAMA_ALL_WARNINGS=OFF \
  -DLLAMA_ALL_WARNINGS_3RD_PARTY=OFF \
  -DGGML_ALL_WARNINGS=OFF \
  -DGGML_ALL_WARNINGS_3RD_PARTY=OFF \
  -DGGML_NATIVE=OFF \
  -DGGML_OPENMP=OFF \
  -DGGML_BLAS=OFF \
  -DGGML_ACCELERATE=OFF \
  -DGGML_METAL=OFF \
  -DGGML_VULKAN=OFF \
  -DGGML_CPU_KLEIDIAI=OFF

"$CMAKE_BIN" --build "$BUILD_DIR" --target llama --parallel "$JOBS"
