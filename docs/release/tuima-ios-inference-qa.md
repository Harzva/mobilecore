# TuiMa iOS real-inference QA

Date: 2026-07-14

## Scope

This acceptance run verifies that the native iOS app loads the frozen TuiMa
reference GGUF through the Objective-C++ llama.cpp bridge and returns generated
model tokens. It is not a mocked response or a UI-only launch check.

## Reference input

- Model: `qwen2.5-0.5b-instruct-q4_k_m.gguf`
- SHA-256: `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`
- Expected reply: `TUIMA_IOS_OK`
- Runtime: llama.cpp commit `063d9c156e816ae3cf62db01f429a07a099afe97`
- Platform: iPhone 17 Pro simulator, iOS 26.5, Apple Silicon host

## Result

- Status: passed
- Reply: `TUIMA_IOS_OK`
- Finish reason: `stop`
- Prompt tokens: 19
- Completion tokens: 4
- Model load: 1,369 ms
- First token: 1,543 ms
- Total inference: 2,092 ms
- Decode speed: 7.29 tokens/s
- Model memory: 462 MB

The app also completed a Release simulator build. The remaining iOS hardware
gate is a signed run on a physical device; simulator timing is evidence of the
runtime path, not a phone performance score.

## Reproduce

```bash
cd ios-app
./scripts/qa-inference-probe.sh
```

The machine-readable report is generated at
`ios-app/build/qa/ios-inference-probe.json` and is intentionally excluded from
Git.
