# TuiMa 0.1.4-rc1

This release candidate adds the MobileCore control and capability contract used by MobileCode v0.1.70.

## Changes

- Load and unload installed GGUF models by public `model_id`.
- Remove absolute model paths from ordinary model and recommendation responses.
- Report active-model runtime, quantization, capabilities, artifact state, and resource preflight through `/health`.
- Report real request totals, failures, uptime, latest decode metrics, and average decode rate through `/metrics`.
- Keep Omni capability claims bound to a verified main-model/projector pair.
- Preserve text-only capability and preflight reporting for ordinary GGUF models.
- Align the Android recommendation UI with the same model-ID load contract.

## Verification

- Android unit tests passed, including ordinary-text active-model preflight.
- Targeted local API instrumentation passed model control, no-path responses, multimodal parsing/rejection, and metrics counters.
- A MobileCode same-emulator lane completed 30 offline real-GGUF requests plus unload/reload, background, low-memory, and process-restart checks.

Physical-device, verified Omni media, sustained thermal, and multi-model switch acceptance remain open gates.
