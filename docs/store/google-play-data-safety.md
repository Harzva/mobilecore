# TuiMa RC2 Google Play Data Safety Draft

This declaration matches the shipped `0.1.3-rc2` configuration and must be reviewed again before enabling a production shared leaderboard or adding third-party SDKs.

## Proposed answers

- Does the app collect or share required user data off-device? **No, in the shipped RC2 configuration.**
- Is all network traffic encrypted? **Not applicable to default local inference.** The API is loopback-only HTTP. Any future remote leaderboard must use HTTPS.
- Can users request deletion? **Local data can be deleted by clearing app storage or uninstalling.** No server-side user profile exists in RC2.
- Does the app contain ads? **No.**
- Is an account required? **No.**

## On-device processing disclosures

The app processes the following locally for core functionality and diagnostics:

- user-selected model and image files;
- prompts and model-generated text;
- device model, Android version, ABI, CPU core count;
- available RAM and storage;
- battery level, charging state, battery temperature, Android thermal status;
- model load time, first-token latency, decode speed, total latency, and benchmark score.

These values are not transmitted off-device by default and therefore are not declared as collected data in the RC2 Play form.

## Change gate

Before configuring `supabase_leaderboard.json` or another production backend:

1. add explicit in-app upload consent and a preview of every transmitted field;
2. publish a retention and deletion process;
3. update the privacy policy;
4. update the Play Data Safety form for device identifiers or diagnostics as applicable;
5. verify that prompts, responses, model files, tokens, and precise identifiers are excluded.
