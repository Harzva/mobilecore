# TuiMa Push Game MVP

Static React/Vite MVP for the MobileCore inference-speed game.

## Run Locally

```bash
cd game-web
npm install
npm run dev
```

Open the Vite URL, usually `http://localhost:5173`.

## Build

```bash
npm run build
npm run preview
```

The production build uses a relative Vite base (`./`), so it can run under the repository GitHub Pages path:

```text
https://harzva.github.io/mobilecore/
```

## Implemented MVP

- Home page with TuiMa brand direction, start actions, progress path, and score stats.
- Challenge page with an 8x8 sokoban board.
- Keyboard controls: arrow keys and WASD.
- Model box pushing with cleared phone targets.
- Demo speed presets: 0.5B 90, 1.5B 70, 3B 45, 7B 28, 14B 12 tok/s.
- Initial scoring: base score + speed score + completion bonus.
- Undo / Reset / Hint controls.
- Challenge calls the MobileCore local API for model-cleared speed measurements, then falls back to demo speed if localhost is unavailable.
- Result Upload saves MobileCore, manual, or fallback entries to `localStorage`; signed MobileCore entries can sync to Supabase when configured.
- Leaderboard ranks entries by inference speed (`tok/s`) and includes Supabase, local, and demo rows.
- Phone Snapshot dynamically shows browser-available device data: CPU activity proxy, CPU cores, memory class, battery, network, viewport, and screen size.
- Custom Grid supports JSON import, export, local save, and direct launch into Challenge.
- Supabase config reads `VITE_SUPABASE_URL` and `VITE_SUPABASE_ANON_KEY`; service role keys must never be used in the frontend.

Browser telemetry is dynamic but not system-level Android CPU utilization; real MobileCore measurements are read through localhost when the Android service is running.

## GitHub Pages Deploy

Use the repository workflow `.github/workflows/game-web-pages.yml`, or run manually:

```bash
cd game-web
npm ci
npm run build
```

Then publish `game-web/dist` through GitHub Pages.

## Next MobileCore API Work

- Connect Supabase project secrets in GitHub Pages environment variables.
- Replace the local signature secret with a server-side verification flow before production ranking.
