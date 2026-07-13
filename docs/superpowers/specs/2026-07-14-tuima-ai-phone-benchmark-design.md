# TuiMa AI Phone Benchmark Release Design

Status: approved product direction, implementation not started

Date: 2026-07-14

Product: TuiMa / MobileCore
First release target: Android RC2 public beta

## 1. Product decision

TuiMa is a standalone AI phone performance benchmark. MobileCore remains the
native inference and measurement engine inside the product. MobileCode is a
separate consumer that can discover and use a healthy MobileCore runtime after
the benchmark contract is stable.

The product promise is deliberately narrow:

> Measure how well this phone runs a frozen local large-language-model workload.

TuiMa does not claim to be a general CPU, GPU, gaming, storage, camera, or whole
device benchmark. The store listing, onboarding, result page, and share cards
must use terms such as "AI inference score" and "local LLM performance" rather
than "overall phone score".

## 2. Release sequence

The program is split into three release slices with one shared contract:

1. Android RC2 public beta: reproducible local-LLM benchmark, dual-layer score,
   evidence report, local history, and opt-in anonymous leaderboard.
2. MobileCode integration: health discovery, local model selection, local/cloud
   routing, and offline fallback using the same MobileCore API contract.
3. iOS parity beta: real GGUF inference, the same benchmark profile semantics,
   simulator and physical-device evidence, and an explicitly separate score
   population until cross-platform calibration is proven.

Android RC2 is the launch-critical slice. iOS remains a technical preview and
must not be marketed as benchmark-equivalent until physical-device inference
and telemetry gates pass.

## 3. Frozen benchmark contract

Official comparable runs use a versioned `BenchmarkSpec`. RC2 introduces
`tuima-llm-benchmark-v2`; the existing `mobilecore-benchmark-v1` remains readable
as legacy history but is not mixed into the v2 leaderboard.

The v2 manifest freezes:

- model ID, source repository, filename, byte size, and SHA-256;
- prompt asset ID and SHA-256;
- llama.cpp revision and MobileCore runtime version;
- context length, thread policy, sampling parameters, output-token limit;
- warm-up count, measured-run count, timeout, and cooldown policy;
- score algorithm version and platform population.

The RC2 reference workload is
`Qwen/Qwen2.5-0.5B-Instruct-GGUF` using
`qwen2.5-0.5b-instruct-q4_k_m.gguf`. The model is downloaded after explicit user
action and verified against the release manifest before it can produce an
official score. Imported models can be tested in an "experimental" lane, but
their results are never compared with the official leaderboard.

The release process must record the model license and attribution beside the
manifest. MobileCore itself must also have an explicit repository/application
license before a stable public release.

## 4. Benchmark profiles

### Quick

Purpose: verify compatibility and give a fast directional score.

- one warm-up run;
- one measured run;
- 64 generated tokens;
- expected duration: 1-2 minutes after the model is ready;
- produces a `quick` score that is not eligible for the official standard
  leaderboard.

### Standard

Purpose: produce the public comparable score.

- one warm-up run;
- three measured runs;
- 128 generated tokens per measured run;
- a controlled cooldown between measured runs;
- expected duration: 4-8 minutes after the model is ready;
- produces the official `standard` score and leaderboard entry.

### Stress

Purpose: expose sustained-performance and thermal degradation.

- one warm-up run;
- ten measured runs;
- 128 generated tokens per measured run;
- no artificial cooldown inside the measured series;
- expected duration: 12-20 minutes;
- produces a separate stress report and does not overwrite the standard score.

Prompt bodies are versioned UTF-8 assets. Reports contain prompt IDs and hashes,
not prompt bodies.

## 5. Preflight and run validity

An official run starts only when all hard gates pass:

- verified reference model and prompt hashes;
- at least 30% battery;
- device is not charging;
- Android thermal status is no worse than `LIGHT` at start;
- enough app-owned storage for the model plus 512 MB working reserve;
- foreground service and localhost API are healthy;
- no other MobileCore benchmark is active.

The app records screen state, charging state, battery percentage, battery
temperature when available, Android thermal status, available memory, free
storage, runtime version, ABI, and thread count. Android thermal status is the
canonical heat signal. Battery temperature must be labelled as battery
temperature and must never be presented as SoC temperature.

A result is invalid rather than merely low-scoring when any of these occur:

- model or prompt hash mismatch;
- process death, native crash, OOM, timeout, or incomplete token generation;
- foreground service restart during a measured run;
- user cancellation;
- charging-state change during the run;
- missing raw metrics required by the selected profile.

Invalid runs still produce a local diagnostic report, but cannot be uploaded to
the official leaderboard.

## 6. Metrics

Each measured run records raw evidence before score calculation:

- model load time;
- prompt token count and prefill tokens per second;
- first-token latency;
- decode tokens per second;
- total duration and generated token count;
- process memory peak and available-memory delta;
- battery percentage delta;
- battery temperature start/peak/end when available;
- Android thermal status start/peak/end;
- throughput retention from the first to the final measured run;
- completed, timeout, OOM, native crash, service restart, and cancellation flags.

Standard profile aggregation uses the median for latency and throughput, the
maximum for memory and thermal status, and explicit counts for failures. Stress
profile additionally reports the p95 latency and throughput-retention ratio.

## 7. Dual-layer score

The canonical score is an integer from 0 to 1000. The consumer-facing headline
score is `canonical_score * 1000`, producing a 0 to 1,000,000 display range.
Only the canonical score is stored, signed, ranked, and used by APIs. The large
score is a deterministic presentation value and cannot drift independently.

The 1000 canonical points are allocated as follows:

| Dimension | Points | Inputs |
| --- | ---: | --- |
| Inference | 350 | decode throughput and prefill throughput |
| Responsiveness | 150 | first-token latency and model load time |
| Memory | 150 | memory peak and pressure relative to available RAM |
| Sustained performance | 200 | throughput retention and thermal status |
| Stability | 150 | completion ratio, OOM, crash, timeout, and service restart |

All normalization curves and anchors belong to the versioned score algorithm,
not to UI code. RC2 calibration uses the physical-device validation matrix and
is frozen before the first public leaderboard accepts uploads. Changing any
weight, anchor, workload, runtime revision, or required metric creates a new
score algorithm version; scores from different versions are never merged.

Every result page must show the five subscores plus raw `tok/s`, first-token
latency, memory peak, thermal status, and battery delta. The headline score is
never the only explanation.

## 8. Architecture

The benchmark implementation is split into independently testable components:

- `BenchmarkManifestRepository`: loads the frozen manifest from the signed app
  package, verifies its code-pinned SHA-256, and then verifies model and prompt
  assets.
- `BenchmarkPreflight`: evaluates battery, charging, thermal, storage, memory,
  model, runtime, and concurrency gates.
- `BenchmarkRunner`: owns the profile state machine, warm-up, measured runs,
  cooldowns, cancellation, and terminal result.
- `TelemetrySampler`: records Android-supported telemetry without interpreting
  scores.
- `BenchmarkAggregator`: converts raw run samples into median, maximum, p95, and
  retention metrics.
- `BenchmarkScoreEngine`: converts an aggregated valid result into the canonical
  five-dimensional score.
- `BenchmarkReportStore`: persists immutable local reports and history.
- `LeaderboardClient`: uploads only eligible, consented, sanitized reports and
  verifies the server response.
- `MobileCoreProviderContract`: exposes health, models, recommendations,
  benchmark summaries, and chat to MobileCode over localhost.

The existing `MainActivity` may render and orchestrate these components, but it
must not own benchmark formulas or raw telemetry collection.

## 9. Data flow

1. User chooses Quick, Standard, or Stress.
2. The app explains the model download and data policy, then obtains consent.
3. Manifest and model hashes are verified.
4. Preflight returns either a runnable snapshot or explicit blocking reasons.
5. The runner starts the foreground service, loads the model, warms up, and
   executes measured runs.
6. Telemetry samples and runtime metrics are appended to an in-memory run log.
7. Aggregation validates completeness and computes raw summary metrics.
8. The score engine scores only a valid summary.
9. An immutable local JSON report is written before any UI success state.
10. The result screen renders the dual-layer score, subscores, raw metrics, and
    recommendation tier.
11. The user can explicitly upload an eligible sanitized report or keep it
    local-only.

## 10. Error handling

All failures terminate in a typed state: `preflight_blocked`, `cancelled`,
`model_invalid`, `runtime_unavailable`, `timeout`, `oom`, `native_crash`,
`service_restarted`, `metrics_incomplete`, or `upload_failed`.

The UI shows the reason, preserves completed raw samples, and provides the one
safe recovery action: retry preflight, redownload the model, reduce experimental
model settings, or retry upload. It never silently changes the reference model,
score algorithm, thread policy, or cloud provider during an official run.

Leaderboard upload failure does not invalidate a local score. Runtime or metric
failure does invalidate the official score.

## 11. Privacy, integrity, and leaderboard policy

Local inference is the benchmark workload. A cloud model is never used to
produce or repair an official score.

An uploaded report contains:

- benchmark/score versions and profile;
- sanitized device model, Android version, ABI, RAM bucket, and core count;
- model ID/hash, runtime revision, raw metrics, score, and validity proof;
- a random installation-scoped pseudonymous ID that the user can reset.

It does not contain prompts, generated text, local paths, account identifiers,
hardware serials, advertising IDs, tokens, cookies, API keys, or raw logs. The
upload screen lists the exact fields and is opt-in.

The client signs the canonical payload for accidental-corruption detection. The
server treats client signatures as integrity hints, not anti-cheat proof. Public
leaderboards label unverified community runs separately from release-team
physical-device reference runs.

## 12. MobileCode integration contract

MobileCode adds a `MobileCore` provider preset with no API key and default base
URL `http://127.0.0.1:8080`. Provider discovery maps MobileCore into four states:

- `unavailable`: health endpoint cannot be reached;
- `ready_no_model`: runtime is healthy but no model is loaded;
- `ready`: healthy, loaded model, recommendation permits local use;
- `degraded`: runtime responds but the latest benchmark or health data says the
  local path is unstable or unsuitable for the requested workload.

MobileCode uses `/health`, `/v1/models`, `/metrics`, `/v1/recommendations`, and
`/v1/chat/completions`. The provider records actual route and fallback reason in
its evidence ledger.

Routing policy:

- user-selected local-only mode never sends content to cloud;
- auto mode uses MobileCore for eligible short/private tasks when state is
  `ready` and the benchmark recommendation permits it;
- if local inference fails, cloud fallback requires an enabled cloud provider
  and the user's previously saved auto-fallback consent;
- if the network is unavailable, cloud requests may fall back to MobileCore
  only when state is `ready`;
- if neither path is available, the request stops with a visible blocked reason;
  it is never silently discarded.

## 13. Automated verification

Implementation follows test-driven development. Required automated coverage:

- unit tests for manifest parsing/hash verification;
- unit tests for every preflight gate and typed failure;
- state-machine tests for Quick, Standard, Stress, cancellation, timeout, OOM,
  native crash, and service restart;
- deterministic aggregation and score golden tests;
- report serialization, redaction, and legacy-v1 migration tests;
- leaderboard eligibility and opt-in upload tests;
- API contract tests for health, model discovery, recommendations, benchmark
  summary, and chat;
- MobileCode provider-state and local/cloud/offline routing tests;
- Android instrumentation tests for battery/thermal/storage telemetry adapters;
- a release smoke test that installs the APK, runs preflight, completes Quick,
  writes a report, and verifies there are no fatal logcat events.

No production benchmark behavior is added without a test that first fails for
the intended missing behavior.

## 14. Physical-device and platform gates

Android RC2 requires sanitized evidence from at least three physical RAM tiers:

- low: 6 GB or below;
- mainstream: 8-12 GB;
- high: 16 GB or above.

Each reference device must complete Standard twice and Stress once while
unplugged. Evidence includes APK hash, install/launch results, model hash,
reports, screen captures, battery/thermal snapshots, storage behavior, and
fatal-log scan. On the same device, the two valid Standard runs must differ by
no more than 7% in canonical score and no more than 10% in median decode
throughput. A device that misses either tolerance is investigated and rerun
before the score algorithm freezes.

iOS parity requires a signed physical-device build, imported reference GGUF,
successful load/chat, repeated Standard-equivalent runs, memory evidence,
thermal-state evidence, and sanitized screenshot/log attachments. Simulator-only
success cannot satisfy this gate.

## 15. RC2 release gates

RC2 is releasable only when all of the following are true:

- manifest, prompt assets, score algorithm, and report schema are versioned;
- reference model download, hash verification, and deletion all work;
- Quick, Standard, and Stress terminate correctly for success and failure;
- automated tests, Android lint, release build, and targeted security scan pass;
- physical-device matrix and calibration report are complete;
- privacy disclosure and explicit upload consent are visible in the product;
- repository/app license and benchmark-model attribution are present;
- tracked files contain no credentials, raw private logs, or private local paths;
- local manifest, lock/state, Git commit, remote branch, tag, GitHub release,
  release APK hash, and runtime smoke evidence identify the same RC2 build.

## 16. Explicit non-goals for RC2

RC2 does not include:

- a claim to whole-phone performance;
- cloud-model scoring;
- VLM, OCR, diffusion, or Phone Agent results in the headline LLM score;
- arbitrary imported-model results in the official leaderboard;
- mandatory login;
- automatic upload;
- cross-version or cross-platform score merging;
- iOS public benchmark parity without physical-device evidence.

These capabilities may have separate labs and reports later, but cannot weaken
the comparability of the frozen Android LLM benchmark.

## 17. Acceptance criteria

The design is implemented when a release tester can install the Android RC2,
download and verify the frozen reference model, pass preflight, complete all
three profiles, inspect dual-layer scores and raw metrics, retain immutable local
reports, opt in to a sanitized leaderboard upload, and reproduce the Standard
score on the physical-device matrix within the frozen tolerance.

MobileCode integration is implemented when the app discovers all four provider
states, runs a real local chat through MobileCore, records the route, performs
consented local-to-cloud and offline cloud-to-local fallback, and produces a
visible blocked reason when neither route is available.

iOS parity is implemented only when a physical iPhone completes real GGUF
inference and the platform-specific benchmark evidence gates above. Until then,
the iOS build remains a preview and cannot be described as release-equivalent.
