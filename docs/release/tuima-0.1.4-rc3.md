# TuiMa 0.1.4-rc3

This release candidate updates MobileCore's pinned llama.cpp/libmtmd runtime for Qwen2.5-Omni compatibility without changing the product boundary: MobileCore performs local inference, while MobileCode owns orchestration, approvals, Phone Use, and ActionEvidence.

## Changes

- Pin llama.cpp to `e1af89a6815737a5db132eee23a94a8ee58553e0`, the upstream merge commit for the Qwen2.5-Omni mmproj conversion fix; no floating branch is used.
- Initialize the newer `mtmd_input_text.text_len` field so image/audio prompt tokenization remains valid after the runtime upgrade.
- Preserve separate runtime capability reporting for text, image, audio, video, and output modalities.
- Add typed `projector_incompatible` and `projector_load_failed` responses. Missing or truncated pinned artifacts remain `artifact_missing` or `checksum_mismatch`.
- Keep the exact Qwen2.5-Omni main GGUF and mmproj sizes, hashes, consent, license, storage, and memory gates unchanged.

## Verification

- Android unit tests, Lint, debug APK, and instrumentation APK compilation passed.
- Local API instrumentation passed public-ID model control and multimodal request handling on an Android 16 arm64 emulator.
- The pinned native runtime loaded Qwen2.5 0.5B, Qwen3 0.6B, and a real Qwen3.5 0.8B GGUF/mmproj pair.
- A real OpenAI-compatible image request completed through the Qwen3.5/libmtmd path after the upstream API adaptation; no crash, ANR, or OOM was observed.
- Real API probes returned distinct `projector_incompatible` and `projector_load_failed` codes.

The emulator cannot hold the 3.64 GB Qwen2.5-Omni pair together with the required safety margin. Physical Android verification of Omni image/audio inference, sustained memory, low-memory recovery, background recovery, and thermal behavior remains a release gate rather than an inferred claim.
