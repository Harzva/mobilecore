# MobileCode ↔ MobileCore local multimodal contract

MobileCode continues to use `http://127.0.0.1:8080/v1`. The provider must read the health/capability contract before enabling attachment UI or selecting the local route. Loopback-only transport is mandatory.

## Capability truth

The health response must expose these booleans independently:

```json
{
  "capabilities": {
    "text_input": true,
    "image_input": true,
    "audio_input": true,
    "video_input": false,
    "text_output": true,
    "audio_output": false
  }
}
```

For the llama.cpp Qwen2.5-Omni-3B route, MobileCode may offer text, image, and audio input only when the model pair is loaded and the corresponding runtime capability is true. It must not show video input or speech-output controls.

Health must also expose the active model ID, runtime/backend and revision, quantization, loaded state, main/mmproj expected digests, local verification state, and memory/storage preflight. Artifact verification should come from `OmniArtifactInstaller.snapshot()`; health requests must not rehash both large files.

## Request rules

Plain string `messages[].content` remains compatible. Structured content may contain `text`, `image_url`, and `input_audio`. MobileCode should prefer data URIs for small controlled attachments and must obey the server’s MIME, byte, duration, and decoded-size limits.

```json
{
  "model": "qwen2.5-omni-3b-local",
  "messages": [
    {
      "role": "user",
      "content": [
        {"type": "text", "text": "请描述图片。"},
        {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,<redacted>"}}
      ]
    }
  ],
  "modalities": ["text"]
}
```

The current Android bridge accepts one image or one audio attachment per request. Audio uses the same structure in a separate request:

```json
{"type":"input_audio","input_audio":{"data":"<redacted>","format":"wav"}}
```

Remote `http(s)` attachment URLs are not a fallback. MobileCode must treat `unsupported_modality`, `media_too_large`, and artifact/preflight failures as typed local-provider results, then follow its configured `localOnly`, `localPreferred`, or `cloudPreferred` policy. `localOnly` must surface the local failure instead of silently uploading the user attachment to a cloud provider.

Requests for `video` input or `audio` output must receive `unsupported_modality`; they must never be accepted and silently downgraded. Raw user text, images, audio, data URI/base64 payloads, and absolute local paths must not enter ordinary logs, benchmark evidence, analytics, or persisted error bodies.

## Artifact installation ownership

MobileCode may display MobileCore’s artifact/preflight state, but MobileCore owns the model directory, consent, license display, Wi-Fi-only download, cancellation, verification, load, and uninstall. MobileCode must not download either GGUF file into its own storage or claim availability when only one artifact exists.

Authenticated lifecycle routes are:

- `POST /mobilecore/model/load` with `{"model_id":"<id>"}` for an already installed GGUF model
- `POST /mobilecore/model/unload` to release the active text model without deleting artifacts

- `GET /mobilecore/omni/status`
- `POST /mobilecore/omni/install`
- `POST /mobilecore/omni/cancel`
- `POST /mobilecore/omni/verify`
- `POST /mobilecore/omni/load`
- `POST` or `DELETE /mobilecore/omni/uninstall`

`install` requires `{"explicit_consent":true,"accepted_license_id":"qwen-research","wifi_only":true}`. It starts an asynchronous app-private download only after preflight passes; MobileCode polls `status` and never receives an artifact path.

MobileCode must switch ordinary installed models by public `model_id`. The legacy `path` load field remains accepted for host QA compatibility, but must not be emitted, persisted, or submitted by MobileCode.
