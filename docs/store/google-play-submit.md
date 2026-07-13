# TuiMa Google Play submission runbook

TuiMa's repository-side publication path is automated through the official
Google Play Developer API. It uploads the already released, upload-signed AAB,
synchronizes the `zh-CN` listing and images, assigns the bundle to a track,
validates the edit, and commits it.

The publisher follows Google's documented edit workflow: create an edit, make
changes inside it, validate, then commit. It uses the official bundle upload,
listing, image, and track endpoints.

## One-time Play Console setup

These account-level actions cannot be created by this repository and must exist
before the API can open an edit:

1. Create the Play Console app with package `com.mobilecore.app`.
2. Enroll the app in Play App Signing and register the existing upload
   certificate.
3. Complete app category, contact details, target audience, content rating, app
   access, ads, and Data Safety declarations.
4. Enable the Google Play Developer API in a Google Cloud project.
5. Create a service account and invite it in Play Console with only the
   app-scoped release and store-presence permissions needed by this workflow.
6. Store its JSON as the GitHub Actions secret
   `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`; never commit the JSON file.

Google's setup guide recommends service accounts for server-to-server access:
<https://developers.google.com/android-publisher/getting_started>

## Local preflight

The preflight performs no network requests and needs no credentials. It checks
the package guard, version code, exact AAB digest, listing fields, asset paths,
asset digests, track payload, and endpoint plan.

```bash
android-app/scripts/google_play_publish.py \
  --aab android-app/app/build/outputs/bundle/release/app-release.aab \
  --confirm-package com.mobilecore.app \
  --dry-run
```

The RC2 config pins the production upload-signed AAB SHA-256, so a debug-signed
or rebuilt artifact is rejected instead of being uploaded accidentally.

## GitHub Actions publication

Run `Publish TuiMa to Google Play` and select:

- `release_tag`: `v0.1.3-rc2`
- `track`: `internal` for the first upload
- `status`: `completed` to serve it to configured internal testers
- `sync_listing`: enabled
- `execute`: disabled for a credential-free remote preflight; enable it only
  when the Play app and service-account secret are configured

The workflow downloads the AAB from the GitHub prerelease rather than rebuilding
it, validates its frozen digest, performs the Play edit, and retains a JSON
publication record for 30 days. Concurrent publication runs are serialized.

The commit call uses `ERROR_IF_IN_REVIEW`; it refuses to cancel an existing
review. Any error before commit triggers a best-effort deletion of the
uncommitted edit.

## Direct authenticated execution

For a secured local environment, provide one of these without printing it:

- `GOOGLE_PLAY_ACCESS_TOKEN`
- `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`
- `GOOGLE_APPLICATION_CREDENTIALS`
- `gcloud auth application-default` credentials

Then run:

```bash
android-app/scripts/google_play_publish.py \
  --aab android-app/app/build/outputs/bundle/release/app-release.aab \
  --confirm-package com.mobilecore.app \
  --track internal \
  --status completed \
  --sync-listing \
  --execute \
  --result android-app/build/google-play-result.json
```

## Manual declarations that remain

The Android Publisher API does not replace the Play Console policy forms.
Review [google-play-data-safety.md](./google-play-data-safety.md) against the
actual binary, then complete Data Safety, target audience, content rating, app
access, and Play App Signing in Console before production review.

For endpoint details, see the official documentation for
[bundle upload](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.bundles/upload),
[track update](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.tracks/update),
and [edit commit](https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit).
