# MobileCore iOS Skeleton

This is the native iOS MVP shell for MobileCore:

- SwiftUI app entry
- Files importer for `.gguf`
- App sandbox model storage at `Documents/MobileCore/models`
- Objective-C++ `LlamaBridge` stub with `backendInfo`, `loadModel`, `chat`, and `unloadModel`
- Foreground localhost API on `127.0.0.1:8080`
- OpenAI-compatible mock routes for `/v1/models` and `/v1/chat/completions`

The bridge is intentionally a stub. It proves the Swift to Objective-C++ boundary, model file flow, and local API shape before wiring real llama.cpp inference.

## Build

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

The localhost listener runs only while the app is open. iOS background service behavior is not part of this skeleton.

## Next Native Step

Replace `MobileCoreApp/Native/LlamaBridge.mm` internals with real llama.cpp calls while keeping the same Swift-facing methods:

- `backendInfo`
- `loadModelAtPath:contextLength:error:`
- `chatWithMessagesJSON:optionsJSON:error:`
- `unloadModel`
