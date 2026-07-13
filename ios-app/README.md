# TuiMa iOS Runtime

This is the native iOS runtime for TuiMa/MobileCore:

- SwiftUI app entry
- Files importer for `.gguf`
- App sandbox model storage at `Documents/MobileCore/models`
- Objective-C++ `LlamaBridge` wired to local llama.cpp with `backendInfo`, `loadModel`, `chat`, and `unloadModel`
- Foreground localhost API on `127.0.0.1:8080`
- OpenAI-compatible routes for `/v1/models` and non-streaming `/v1/chat/completions`

The app builds llama.cpp as a local static library through `scripts/build-llama.sh`. The script reuses the repository's ignored `android-app/third_party/llama.cpp` checkout so the Android and iOS paths stay on the same pinned llama.cpp revision.

## Build

Restore llama.cpp first if it is not already present:

```bash
cd ../android-app
./scripts/bootstrap-llama.sh
```

```bash
cd ios-app
xcodebuild -project MobileCoreiOS.xcodeproj \
  -scheme MobileCoreiOS \
  -destination 'generic/platform=iOS Simulator' \
  build
```

Or open `MobileCoreiOS.xcodeproj` in Xcode and run the `MobileCoreiOS` scheme.

## Use

1. Run the app in the foreground.
2. Tap `Import GGUF` and choose a `.gguf` file from Files.
3. Tap `Load First` or the row-level `Load` button.
4. Tap `Start API`.

Routes:

```bash
curl -H "Authorization: Bearer local" \
  http://127.0.0.1:8080/v1/models

curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer local" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "local-model",
    "messages": [{"role": "user", "content": "Hello from MobileCore iOS"}],
    "max_tokens": 32
  }'
```

The localhost listener runs only while the app is open. iOS background service behavior is not part of this skeleton. Streaming chat is not implemented yet.

## Automated real-inference probe

The simulator probe builds the native app, verifies the frozen Qwen2.5 model
digest, installs the app, copies the GGUF into its sandbox, runs llama.cpp
inference, and validates a structured report:

```bash
./scripts/qa-inference-probe.sh
```

The report is written to `build/qa/ios-inference-probe.json`. Override
`TUIMA_IOS_MODEL_PATH` to test another local GGUF; when doing so, also provide
its expected digest through `TUIMA_IOS_MODEL_SHA256`.

## Next Native Step

Add configurable sampling, streaming tokens, Metal acceleration, and device-side benchmark persistence.
