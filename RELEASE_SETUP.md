# Google Play Store Release Setup

This document covers the one-time setup needed for automated releases to the Google Play Store. For how the release flow works day to day, see the **Release Process** section of [CLAUDE.md](CLAUDE.md).

## Prerequisites

1. **Google Play Console Account**: Ensure you have a Google Play Console developer account
2. **App Registration**: The app must be registered in Google Play Console under `com.bizzarosn.heightmark`
3. **Google Play App Signing**: Enable Google Play App Signing for the app (recommended)

## Required GitHub Secrets

Add these secrets to the GitHub repository settings (`Settings > Secrets and variables > Actions`). All five are consumed by [`.github/workflows/release.yml`](.github/workflows/release.yml).

### `SERVICE_ACCOUNT_JSON`
1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select existing project
3. Enable the Google Play Android Developer API
4. Create a service account:
   - Go to `IAM & Admin > Service Accounts`
   - Click `Create Service Account`
   - Fill in the details and click `Create`
   - Skip granting roles for now, click `Done`
5. Generate and download the JSON key:
   - Click on the created service account
   - Go to `Keys` tab
   - Click `Add Key > Create New Key`
   - Select `JSON` and click `Create`
   - Download the JSON file
6. Link the service account to Google Play Console:
   - Go to [Google Play Console](https://play.google.com/console)
   - Select your app
   - Go to `Setup > API Access`
   - Click `Link` next to Google Cloud Project
   - Select your project and click `Link`
   - Grant access to the service account:
     - Find your service account in the list
     - Click `Grant Access`
     - Select appropriate permissions (Release Manager recommended)
7. Copy the entire JSON file content and paste it as the `SERVICE_ACCOUNT_JSON` secret

### Signing secrets

| Secret | Contents |
| --- | --- |
| `KEYSTORE_BASE64` | The upload keystore, base64-encoded. The workflow decodes it to `app/release.keystore` and deletes it after the build. |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Alias of the signing key inside the keystore |
| `KEY_PASSWORD` | Password for that key |

The workflow exports these as the `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` environment variables that `app/build.gradle.kts` reads. If any is missing, the signing config is left empty and the build produces an **unsigned** artifact — which is why local `assembleRelease`/`bundleRelease` output is unsigned.

## Package Name Configuration

The `packageName` in `.github/workflows/release.yml` must match the `applicationId` in `app/build.gradle.kts` and the app's listing in Play Console:

```yaml
packageName: com.bizzarosn.heightmark
```

Note that debug builds carry an `applicationIdSuffix` of `.debug`, so they install alongside release builds and are irrelevant to Play uploads.

## Release Track Configuration

The workflow releases to the `internal` track. Change `tracks:` in the workflow file to target another track:

- `internal`: Internal testing track
- `alpha`: Alpha testing track
- `beta`: Beta testing track
- `production`: Production track (live on Play Store)

## Creating a Release

**Releases are automatic. There is no version to bump and no tag to push.**

Every merge to `main` that passes CI produces a release:

1. The merge triggers the "Android CI" workflow on `main` (Trivy security scan, then lint, unit tests, `assembleDebug`, `assembleRelease`, then instrumented tests).
2. When "Android CI" completes **successfully** on `main`, `release.yml` starts via a `workflow_run` trigger. A failed CI run releases nothing.
3. `release.yml` computes the version itself from its own `run_number` — `versionCode = 10000 + run_number`, `versionName = "1.0.<run_number>"` — and passes them to Gradle as `-PversionCode` / `-PversionName`. The values in `app/build.gradle.kts` (`versionCode 4`, `versionName "1.0.0-dev"`) are only local-build fallbacks; editing them has no effect on releases.
4. The signed AAB is uploaded to the Play Store `internal` track and a GitHub release is created, tagged `v<versionName>`, with auto-generated notes and the AAB attached. The tag is an *output* of the release, not its trigger.

The only thing worth editing by hand before a release is the release notes in `metadata/whatsnew/whatsnew-en-US`, which the workflow passes to Play as `whatsNewDirectory`.

To change the major/minor version, edit `BASE_CODE` and `VERSION_PREFIX` in `release.yml`. To roll back, revert the offending commit on `main` and let the next release go out. Both are described in more detail in [CLAUDE.md](CLAUDE.md).

## Workflow Features

- **Trigger**: `workflow_run` on a successful "Android CI" run on `main` — not on tags
- **Quality Checks**: run in `android_build.yml`, not here; `release.yml` runs no lint or tests of its own and relies on the CI success condition as its gate
- **Versioning**: derived from the release workflow's `run_number`, with no manual bump
- **Serialization**: a `play-store-release` concurrency group keeps releases sequential, because the Play Publishing API allows only one open edit per app
- **Play Store Upload**: uploads the AAB to the `internal` track with `inAppUpdatePriority: 2`
- **GitHub Release**: creates a tagged GitHub release with auto-generated notes and the AAB attached
- **Artifact Storage**: uploads the AAB as a workflow artifact for 30 days

## Troubleshooting

### Common Issues

1. **Invalid Package Name**: Ensure package name matches exactly with Play Console
2. **Service Account Permissions**: Service account needs "Release Manager" role
3. **API Not Enabled**: Ensure Google Play Android Developer API is enabled
4. **First Release**: First release may need to be done manually through Play Console
5. **No release ran after a merge**: check that "Android CI" *succeeded* on `main` — `release.yml` is skipped entirely when the upstream run's conclusion is anything else
6. **Version code already used**: `run_number` only ever increases, but a re-run of an old release workflow reuses its number; cut a new release instead of re-running an old one

### Debug Steps

1. Check GitHub Actions logs for detailed error messages
2. Verify service account JSON format and permissions
3. Ensure Google Play Console app is properly configured
4. Test service account access using Play Console API

## Security Notes

- Never commit service account JSON files or keystores to the repository
- Use GitHub Secrets for all sensitive information
- Regularly rotate service account keys
- Monitor API usage in Google Cloud Console

## Support

For issues with:
- **Google Play Console**: Check [Google Play Console Help](https://support.google.com/googleplay/android-developer)
- **GitHub Actions**: Check [GitHub Actions Documentation](https://docs.github.com/en/actions)
- **Upload Action**: Check [upload-google-play Action](https://github.com/r0adkll/upload-google-play)
