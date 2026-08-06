# TuiMa UI reference

The UI refresh uses a GPT Image 2 design board as a visual reference, then validates the responsive Android implementation with emulator screenshots from the actual APK.

## Design references

| Initial direction | Instrument panel refinement |
|---|---|
| ![Initial TuiMa design reference](tuima-ui-gpt-image-2-v1.png) | ![TuiMa instrument panel reference](tuima-ui-gpt-image-2-v2.png) |

## Android implementation

| Home | Benchmark |
|---|---|
| ![Home](tuima-ui-implementation-home.png) | ![Benchmark](tuima-ui-implementation-benchmark.png) |

| Results | Models |
|---|---|
| ![Results](tuima-ui-implementation-results.png) | ![Models](tuima-ui-implementation-models.png) |

Implementation source:

- `android-app/app/src/main/kotlin/ai/mobilecore/MainActivity.kt`
- `android-app/app/src/main/kotlin/ai/mobilecore/ui/TuiMaTheme.kt`
- `android-app/app/src/main/kotlin/ai/mobilecore/ui/TuiMaComponents.kt`
- `android-app/app/src/main/kotlin/ai/mobilecore/ui/ModelLifecycleUi.kt`
- `android-app/app/src/main/kotlin/ai/mobilecore/runtime/ModelLoadStatusContract.kt`

The second refinement turns TuiMa into a compact on-device AI instrument panel: the home screen exposes device, runtime, telemetry, score, and one primary action in the first viewport; benchmark and result details use flatter section hierarchy; model recommendations use compact rows with separate status and action colors; bottom navigation is a stable full-width control instead of another floating card.

The screens use content-driven heights, adaptive page gutters, wrapping text, and minimum 48 dp primary actions to avoid collisions on narrow displays and at larger font scales. The model surfaces distinguish not downloaded, downloading, downloaded, loading, loaded, and failure states using runtime-confirmed state instead of treating a local file as an active model.

Benchmark profiles, scoring, and result calculations remain unchanged.
