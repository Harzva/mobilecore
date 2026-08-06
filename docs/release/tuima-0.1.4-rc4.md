# TuiMa 0.1.4-rc4

This release candidate adds a strict MobileCoreClient v2 compatibility handshake without changing the product boundary: MobileCore performs local inference, while MobileCode owns orchestration, approvals, Phone Use, and ActionEvidence.

## Changes

- Publish `protocol.name`, major/minor, and the supported client-major range in every `/health` response.
- Add authenticated `POST /mobilecore/inference/cancel`, forward it to the native llama.cpp cancellation flag, and publish completed/cancel counters in `/metrics`.
- Return a typed `cancelled` failure when native text decoding is interrupted instead of treating a partial run as a successful completion.
- Serialize access to the shared llama context and reject overlapping inference or model-lifecycle calls as `runtime_busy`, preventing concurrent QA/client requests from aborting the native process.
- Apply the GGUF chat template before text generation so instruct models receive a valid assistant-generation prompt.
- Keep the MobileCore app version independent from the local control protocol version.
- Require MobileCode v2 to reject missing or incompatible protocol declarations before model control or inference.
- Preserve the pinned llama.cpp/libmtmd revision and the existing text, image, and audio capability truth model.

## Verification gates

- JVM contract tests must verify the exact v2 protocol fields.
- The Android API instrumentation suite must observe the protocol declaration from a running server.
- A paired MobileCode build must reject missing/unsupported protocols and accept `mobilecore.local` v2.
- Emulator pairing remains development evidence. Sustained thermal, low-memory, background recovery, and 30-task acceptance still require a physical Android device.
