# Google Play Store Release Setup

This document covers the one-time setup needed for automated releases to the Google Play Store. For how the release flow works day to day, see the **Release Process** section of [CLAUDE.md](CLAUDE.md).

## Prerequisites

1. **Google Play Console Account**: Ensure you have a Google Play Console developer account
2. **App Registration**: The app must be registered in Google Play Console under `com.bizzarosn.heightmark`
3. **Google Play App Signing**: Enable Google Play App Signing for the app (recommended)

## Required GitHub Secrets

Add these secrets to the GitHub repository settings (`Settings > Secrets and variables > Actions`). All eight are consumed by [`.github/workflows/release.yml`](.github/workflows/release.yml).

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
| `KEYSTORE_BASE64` | The upload keystore, base64-encoded. The workflow decodes it to `$RUNNER_TEMP/release.keystore`, outside the workspace, and deletes it after the build. |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Alias of the signing key inside the keystore |
| `KEY_PASSWORD` | Password for that key |

The workflow exports these as the `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` environment variables that `app/build.gradle.kts` reads. If any is missing, the signing config is left empty and the build produces an **unsigned** artifact — which is why local `assembleRelease`/`bundleRelease` output is unsigned.

### Sideload signing secrets

GitHub release APKs, the ones Obtainium installs, use a dedicated sideload key. Play App Signing re-signs Play installs with Google's key anyway, and a separate key keeps a leaked or reset upload key away from sideloaded users.

| Secret | Contents |
| --- | --- |
| `SIDELOAD_KEYSTORE_BASE64` | The sideload keystore, base64-encoded. Decoded to `$RUNNER_TEMP` and deleted after the build. |
| `SIDELOAD_KEY_ALIAS` | Alias of the signing key inside the keystore |
| `SIDELOAD_KEY_PASSWORD` | Keystore password. `keytool` writes PKCS12, which has one password for store and key, so the workflow passes it as both. |

The "Verify sideload APK signer" step fails the release unless the APK's signer matches `EXPECTED_CERT_SHA256`. That pin must equal the fingerprint in `Readme.md`.

**This key can never be replaced.** Android rejects updates from a different signer, so a new key strands every sideloaded install. Keep an offline backup of the keystore and its password.

Setup:

1. `keytool -genkeypair -v -keystore heightmark-sideload.jks -alias heightmark -keyalg RSA -keysize 4096 -validity 10000`
2. `keytool -list -v -keystore heightmark-sideload.jks -alias heightmark`, then copy the `SHA256:` line into `EXPECTED_CERT_SHA256` and `Readme.md`. Do not pipe this command: `keytool` echoes the password when its output is not a terminal.
3. `base64 -i heightmark-sideload.jks | gh secret set SIDELOAD_KEYSTORE_BASE64`, then `gh secret set` the other two.

## Reproducible builds and F-Droid

The release APK is reproducible. A rebuild of the release commit with the same `-PversionName` and `-PversionCode` matches the GitHub release byte for byte, apart from the APK Signing Block. `Readme.md` has the steps a user runs. The `Reproducible Release APK` CI job rebuilds on macOS and compares with the Linux build on every PR and every push to `main`.

What keeps it reproducible:

- Native libraries ship unstripped (`packaging.jniLibs.keepDebugSymbols` in `app/build.gradle.kts`). AGP strips them only when an NDK is installed, so stripping would make the bytes depend on the build machine.
- The baseline profile is a committed input. CI never regenerates it.
- AGP, Gradle and every dependency are pinned. The JDK major version is pinned. Builds with Temurin 21.0.9 and 21.0.12 produced identical dex.
- AGP records the commit hash in `META-INF/version-control-info.textproto`. Rebuilders build from a git clone at the release tag.

### F-Droid with the developer signature

F-Droid can publish the sideload APK itself instead of signing its own build. F-Droid builds the tagged commit from source, copies the signature from the GitHub release APK onto its build with `apksigcopier`, and publishes the GitHub APK only if the two match. A mismatch publishes nothing for that version. There is no fallback to an F-Droid signature. Users can then move between F-Droid, Obtainium and GitHub without an uninstall, because every channel except Play has the same signer.

Submission is a merge request to [fdroiddata](https://gitlab.com/fdroid/fdroiddata) that adds `metadata/com.bizzarosn.heightmark.yml`. Run `fdroid lint` and `fdroid build` on it before opening the request. A starting point:

```yaml
Categories:
  - Navigation
License: MIT
SourceCode: https://github.com/arunderwood/heightmark
IssueTracker: https://github.com/arunderwood/heightmark/issues

AutoName: HeightMark

RepoType: git
Repo: https://github.com/arunderwood/heightmark.git
Binaries: https://github.com/arunderwood/heightmark/releases/download/v%v/heightmark-v%v.apk

Builds:
  - versionName: 1.0.180
    versionCode: 10180
    commit: v1.0.180
    subdir: app
    gradle:
      - yes
    prebuild: printf 'versionName=$$VERSION$$\nversionCode=$$VERCODE$$\n' >> ../gradle.properties

AllowedAPKSigningKeys: 2171c54bad49794a3bc357a62b149cdb9c08a8c06582ebcbba5390a695a3a868

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v1\.0\.\d+$
UpdateCheckData: '|v1\.0\.(\d+)||v(.+)'
VercodeOperation:
  - '%c + 10000'
CurrentVersion: 1.0.180
CurrentVersionCode: 10180
```

- The version exists only in `release.yml`, so the recipe derives it from the tag. `prebuild` writes it to `gradle.properties`, because `gradleprops` gets no variable substitution. `VercodeOperation` must track `BASE_CODE`.
- `AllowedAPKSigningKeys` is the sideload certificate's SHA-256 from `EXPECTED_CERT_SHA256`, in lowercase hex without colons.
- F-Droid builds on Debian with Debian's OpenJDK 21. Its scanner deletes `gradle/gradle-daemon-jvm.properties`, so the Temurin pin does not apply there.
- F-Droid reads the store listing from `fastlane/metadata/android/en-US/` (`title.txt`, `short_description.txt`, `full_description.txt`, `images/`). The repository has none yet. `metadata/whatsnew/` is Play-only.

[IzzyOnDroid](https://izzyondroid.org/docs/reproducibleBuilds/EstablishApp/) is a second option. It publishes the GitHub APK and rebuilds each release with its own verifier.

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

1. The merge triggers the "Android CI" workflow on `main` (lint, unit tests, `assembleDebug`, `assembleRelease`, and instrumented tests).
2. When "Android CI" completes **successfully** on `main`, `release.yml` starts via a `workflow_run` trigger. A failed CI run releases nothing.
3. `release.yml` computes the version itself from its own `run_number` — `versionCode = 10000 + run_number`, `versionName = "1.0.<run_number>"` — and passes them to Gradle as `-PversionCode` / `-PversionName`. The values in `app/build.gradle.kts` (`versionCode 4`, `versionName "1.0.0-dev"`) are only local-build fallbacks; editing them has no effect on releases.
4. The signed AAB is uploaded to the Play Store `internal` track. A separate `publish` job then attests the AAB and the sideload APK and creates a GitHub release, tagged `v<versionName>`, with auto-generated notes and both files attached. The tag is an *output* of the release, not its trigger.

The only thing worth editing by hand before a release is the release notes in `metadata/whatsnew/whatsnew-en-US`, which the workflow passes to Play as `whatsNewDirectory`.

To change the major/minor version, edit `BASE_CODE` and `VERSION_PREFIX` in `release.yml`. To roll back, revert the offending commit on `main` and let the next release go out. Both are described in more detail in [CLAUDE.md](CLAUDE.md).

## Workflow Features

- **Trigger**: `workflow_run` on a successful "Android CI" run on `main` — not on tags
- **Quality Checks**: run in `android_build.yml`, not here; `release.yml` runs no lint or tests of its own and relies on the CI success condition as its gate
- **Versioning**: derived from the release workflow's `run_number`, with no manual bump
- **Serialization**: a `play-store-release` concurrency group keeps releases sequential, because the Play Publishing API allows only one open edit per app
- **Play Store Upload**: uploads the AAB to the `internal` track with `inAppUpdatePriority: 2`
- **Sideload APK**: `assembleRelease` with the sideload key, checked against the pinned certificate before anything is published
- **GitHub Release**: a separate `publish` job attests both files with `actions/attest` and creates a tagged release with auto-generated notes, the APK, and the AAB. It holds the write and `id-token` permissions, so the third-party Play action never does
- **Artifact Storage**: uploads the AAB and APK as a workflow artifact for 30 days

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
