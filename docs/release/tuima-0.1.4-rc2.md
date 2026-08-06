# TuiMa 0.1.4-rc2

This release candidate extends the MobileCore control API from text-only GGUF switching to compatible local vision pairs.

## Changes

- Discover `mmproj-*.gguf` artifacts without listing them as standalone language models.
- Auto-pair exactly one same-directory, same-family projector with its main GGUF.
- Allow an optional public `projector_id` and reject incompatible explicit pairs.
- Load the projector through llama.cpp/libmtmd and report actual image capability through `/health`.
- Expose model-level projector metadata and candidate capabilities without returning local paths.
- Keep generic imported artifacts marked unverified unless a trusted digest flow verifies them.

## Verification

- Android unit tests and debug/test APK builds passed.
- Local API instrumentation passed public-ID control, incompatible-projector rejection, multimodal parsing, typed rejection, and metrics checks.
- A real Qwen3.5 0.8B GGUF/mmproj pair loaded on an Android 16 arm64 emulator and completed an OpenAI-compatible image request through the unified API with no crash, ANR, or OOM.
- The transport/runtime result is not an accuracy claim: the controlled animal image was classified incorrectly, so a task-level vision benchmark remains required.
- A separate two-model dual-app emulator lane passed 30 offline MobileCode-to-MobileCore requests and a real Qwen2.5-to-Qwen3 model switch.

Physical-device thermal/background acceptance, verified Qwen2.5-Omni image/audio acceptance, and vision accuracy remain open gates.
