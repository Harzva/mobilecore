# TuiMa UI reference

The UI refresh uses a GPT Image 2 design board as a visual reference, then validates the Android implementation with emulator screenshots from the actual APK.

## Design reference

![TuiMa three-screen design reference](tuima-ui-gpt-image-2-v1.png)

## Android implementation

| Home | Benchmark | Results |
|---|---|---|
| ![Home](tuima-ui-implementation-home.png) | ![Benchmark](tuima-ui-implementation-benchmark.png) | ![Results](tuima-ui-implementation-results.png) |

Implementation source:

- `android-app/app/src/main/kotlin/ai/mobilecore/MainActivity.kt`
- `android-app/app/src/main/kotlin/ai/mobilecore/ui/TuiMaTheme.kt`
- `android-app/app/src/main/kotlin/ai/mobilecore/ui/TuiMaComponents.kt`

The design changes presentation and interaction only. Benchmark profiles, state transitions, scoring, and result calculations remain unchanged.
