# TuiMa Push Game MVP

Static React/Vite MVP for the MobileCore benchmark game.

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
- Mock benchmark speeds: 0.5B 90, 1.5B 70, 3B 45, 7B 28, 14B 12 tok/s.
- Initial scoring: base score + speed score + completion bonus.
- Undo / Reset / Hint controls.
- Result Upload saves submissions to `localStorage`.
- Leaderboard reads local submissions and includes a demo row.
- Custom Grid supports JSON import, export, and local save.
- Supabase config placeholders are present in `src/config.ts`; service role keys must never be used in the frontend.

## GitHub Pages Deploy

Use the repository workflow `.github/workflows/game-web-pages.yml`, or run manually:

```bash
cd game-web
npm ci
npm run build
```

Then publish `game-web/dist` through GitHub Pages.

## Next MobileCore API Work

- Replace `runMockBenchmark()` with calls to MobileCore local API.
- Add CORS support for GitHub Pages origin in MobileCore.
- Sign benchmark results before upload.
- Add Supabase anonymous insert/read with RLS for real shared leaderboard.
