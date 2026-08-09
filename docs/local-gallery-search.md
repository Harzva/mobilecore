# Local gallery text search core

The Android core in `ai.mobilecore.gallery.search` implements a local CLIP retrieval path. It is
synchronous by design: Activities should own a worker executor and post typed progress/results to
the existing `GallerySearchStateMachine` on the main thread.

## Required artifacts

`AndroidGallerySearchHost.open(...)` discovers exact, paired artifacts:

- `openai-clip-vit-b16-image.onnx`
- `openai-clip-vit-b16-text.onnx`
- `vocab.json`
- `merges.txt`
- `tokenizer_config.json`

The Oxford-Pets fixed-label embedding sidecar is not accepted as a replacement for the text
encoder. Missing image, text, or tokenizer artifacts produce distinct failure codes. The runtime
digest covers both ONNX graphs and all tokenizer files; a changed digest invalidates the old image
index. Only the audited complete-artifact digest is shown as a verified upstream identity. An
ABI-compatible user import remains usable but is explicitly shown as **身份未验证** in the product
UI and as `identity_verified=false` in new evidence reports.

## Host wiring

```kotlin
val opened = AndroidGallerySearchHost.open(
    contentResolver = contentResolver,
    modelsDirectory = clipModelsDir,
    tokenizerDirectory = clipTokenizerDir,
    indexDirectory = File(noBackupFilesDir, "gallery-search"),
)
```

For `Ready`, keep and close the host with the Activity/service lifecycle. Run
`buildOrUpdateIndex(...)` and `search(...)` off the main thread. Use
`GalleryPhotoSelection.MediaStoreImages` only after media permission, or
`GrantedContentUris` with Photo Picker/SAF grants. A `GalleryCancellationToken` cancels indexing;
completed vectors are atomically checkpointed and reused on retry. Call `clearIndex()` before an
explicit retry from `INDEX_CORRUPT`.

On Android 14+, a selected-photos grant is represented separately from full-library access, and the
ready state offers **选择更多并更新索引** to reopen the system selection surface. The Activity
releases CLIP sessions from `onStop`; a process-wide release barrier prevents a replacement Activity
from opening a second pair of ONNX sessions before the previous serialized close completes.

Suggested UI event mapping:

- index progress -> `GallerySearchEvent.IndexProgress`
- completed -> `GallerySearchEvent.IndexCompleted`
- typed failure/cancel -> `GallerySearchEvent.IndexFailed`
- runtime ready -> `GallerySearchEvent.ModelsReady` using `host.descriptor`
- query results -> `GallerySearchEvent.SearchCompleted`
- query blocked -> `GallerySearchEvent.SearchFailed`

## Privacy boundary

The product path opens only `content://` URIs through `ContentResolver`. It does not initiate HTTP
requests. The private binary index stores normalized vectors plus minimum content identity and
change metadata. It deliberately omits image bytes, EXIF, filenames, generated captions, and all
queries. Put its directory under app-private storage, never shared/external storage. Large images
are bounds-decoded and power-of-two sampled before allocating pixels.

The query field explicitly says **“MobileCore 不上传照片、查询或索引”**, opts out of Android
autofill/state saving, disables personalized-learning and extract-UI IME flags, and requests no
text suggestions. These flags keep MobileCore from feeding the query into app persistence or
autofill, but an independently installed keyboard remains outside MobileCore's trust boundary and
is governed by that keyboard's own privacy settings.

## G2D and Oxford-Pets boundary

`AgenticGalleryRetrieval` exposes a closed tool set: `clip_direct` and `candidate_verifier`. A
reranker can only reorder IDs already returned by CLIP. Missing/failed/empty/escaped reranker output
falls back to CLIP, and only IDs actually returned by a successful reranker are marked verified.
Router exceptions and explicit timeout failures also fail closed to the original cosine-ranked CLIP
candidates; the verifier is not invoked without a valid routing decision. Router implementations
remain responsible for enforcing their deadline and reporting it as a timeout.

`OxfordPetsRetrievalAdapter` creates deterministic official-order 37-image and 370-image retrieval
plans with 37 breed prompts and closed relevance sets. Its unit tests validate protocol selection
and metrics, but only the opt-in Android instrumentation harness counts as ONNX inference evidence.

The product UI currently uses `clip_direct` only. The closed `candidate_verifier` tool contract is
implemented and tested, but no VLM/G2D reranker is connected to the gallery UI yet. Consequently,
the UI reports cosine-ranked CLIP candidates and never labels them as G2D-verified.

## ARM64 emulator evidence

On 2026-08-09 the opt-in instrumentation harness ran both fixed scales with the real paired ONNX
encoders and tokenizer on `sdk_gphone64_arm64`, API 36, ABI `arm64-v8a`:

| Scale | Images | Queries | Recall@1 | Recall@5 | MRR@100 | Index | Search |
|---|---:|---:|---:|---:|---:|---:|---:|
| smoke | 37 | 37 | 0.7838 | 0.9459 | 0.8568 | 10,533 ms | 1,845 ms |
| pilot | 370 | 37 | 0.8108 | 1.0000 | 0.8874 | 95,060 ms | 3,431 ms |

The combined model-artifact digest was
`047ea6bcdda8b9f5b8fe9c04aa86106ef32f58731831d847eaee7dfc1f3b7046`.
The raw reports are tracked under `docs/evidence/gallery-search/`. They explicitly set
`environment.class=android_emulator` and `physical_device_claim=false`; these measurements are not
physical-phone performance evidence.

`MRR@100` is named with its actual cutoff: the harness asks for at most 100 candidates (all 37 for
the smoke set). Before a report can pass, a sanity gate now requires Recall@1 to exceed twice the
closed-gallery random baseline, at least 25% distinct Top-1 IDs, and no single Top-1 ID in more than
25% of queries. This catches random-level output and obvious fixed-output/mode collapse; it is not a
paper-result acceptance threshold. Reports also identify the MobileCore app build, ONNX Runtime
version, configured CPU execution provider, and providers available in that runtime. The current
execution-provider field means the harness used default ORT CPU sessions; it does not claim NNAPI,
GPU, or NPU acceleration.

Run the opt-in harness only after seeding the exact CLIP files into `files/vision/models` and the
official Oxford-Pets split/images into the app external `g2d/retrieval` directory:

```bash
adb shell am instrument -w \
  -e runGalleryRetrieval true \
  -e galleryEnvironment emulator \
  -e galleryScale smoke \
  -e class ai.mobilecore.gallery.search.OxfordPetsGalleryRetrievalInstrumentedTest \
  com.mobilecore.app.test/androidx.test.runner.AndroidJUnitRunner
```

Use `galleryScale=pilot` for 370 images. The harness dynamically classifies the target and refuses
to write a report unless it matches `galleryEnvironment=emulator|physical`; this release's tracked
evidence requires `emulator`. A normal connected test run skips this opt-in case instead of
replacing model inference with fixtures.
