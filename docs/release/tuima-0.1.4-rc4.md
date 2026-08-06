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

## Verified build

- Release: [TuiMa 0.1.4 RC4](https://github.com/Harzva/mobilecore/releases/tag/v0.1.4-rc4), targeting commit `07abdf0d2008e734ff35d5ba64019207efb0656a`.
- GitHub Actions: [run 31127904733](https://github.com/Harzva/mobilecore/actions/runs/31127904733) passed JVM tests, Android Lint, pinned llama.cpp bootstrap, and APK/AAB release builds with `upload_signed=true`.
- Official upload-signed APK SHA-256: `6ea2b9c80e55d7c98e0ac2f85bf003141472d73328cb10c34acee33619018df5`.
- Official upload-signed AAB SHA-256: `02c672bab8d6453e8c25b90a20b8d6f11ae2b93d4273f9ecbec6799f75539d17`.
- The APK verifies with Signature Scheme v2 and the TuiMa Upload certificate (`CN=TuiMa Upload, OU=Harzva, O=Harzva, L=Shanghai, ST=Shanghai, C=CN`), certificate SHA-256 `6d7661f82f2e6d415e8276b61526287ed373ee5b992cac7ea57f1e9f7a192cb0`.
- On an Android 16 ARM64 emulator, the local API instrumentation suite passed 2/2 tests, a real Qwen2.5 0.5B request returned the exact controlled response, and a long native request stopped with the typed `cancelled` failure after the authenticated cancel call.
- MobileCode `0.1.76+66` accepts `mobilecore.local` v2 and rejects missing, malformed, differently named, or unsupported protocol declarations before local control or inference.
- A Semgrep secrets scan reported zero findings across tracked MobileCore source files. Raw prompts, media, credentials, and generated output are not release evidence.

The separately labeled debug-signed APK remains QA-only. Use the upload-signed APK/AAB and published SHA-256 manifest for the official rc4 build.

## Remaining gates

Emulator pairing remains development evidence. Verified Qwen2.5-Omni image/audio, sustained thermal behavior, low-memory recovery, background recovery, controlled-account login, and the 30-task dual-app acceptance still require a compatible physical Android device. MobileCore remains inference-only and cannot bypass MobileCode approvals or directly click, log in, pay, or place an order.
