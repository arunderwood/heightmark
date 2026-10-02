---
paths:
  - ".github/workflows/**"
  - "RELEASE_SETUP.md"
---

# CI and release

Mechanism detail for a single workflow step lives in a YAML comment next to that step. This file holds what spans steps or files. One-time secret and Play Console setup is in `RELEASE_SETUP.md`.

## Release flow

- Every merge to `main` releases if CI passes. `release.yml` triggers through `workflow_run` when "Android CI" completes successfully for a push to `main`.
- `check-tip` gates the release. Its `if:` requires `workflow_run.event == 'push'` from this repository, because the `branches` filter matches the triggering run's head branch and a fork PR from a branch named `main` would pass it. It releases only `main`'s tip: a superseded commit is skipped with a `::notice::`, since the newer commit ships its changes.
- The release job checks out `workflow_run.head_sha` (the commit CI tested), builds a signed AAB and uploads it to the Play **internal** track. It also builds a sideload APK signed with a dedicated key that must match `EXPECTED_CERT_SHA256`.
- A separate `publish` job is the only one with write and `id-token` permissions. It attests both files and creates the GitHub release at that SHA.
- Concurrency group `play-store-release` serializes releases, because Play allows one open edit.
- Obtainium tracks the GitHub releases with no config: tag `v<versionName>` matches versionName, and each release has one APK.
- The sideload fingerprint appears in `release.yml` and `Readme.md` and must match in both. Never rotate the sideload key.
- Release signing reads `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Both keystores decode to `$RUNNER_TEMP`, outside the workspace.

## Versioning

- `versionCode = BASE_CODE + run_number` (both defined in `release.yml`). `versionName = VERSION_PREFIX.run_number`. `run_number` is the release workflow's own counter.
- To bump major or minor, edit `VERSION_PREFIX` and `BASE_CODE` together in `release.yml`, keeping `BASE_CODE` above every versionCode already uploaded to Play.

## Rollback

Revert on `main` (`git revert <sha>`, or `git revert -m 1 <merge-sha>` for a merge) and push. A new release ships the fix.

## Android CI (`android_build.yml`)

- Triggers on push to `main` and on all PRs, with no base-branch filter, so stacked PRs get the gates.
- PR runs cancel when superseded. `main` runs get a group per commit so none is cancelled.
- Every job sets `timeout-minutes`.
- **build-and-test** runs `lintDebug testDebugUnitTest assembleDebug assembleRelease` in one job. `assembleRelease` exercises R8 and resource shrinking on every PR. It is unsigned there and never uploaded.
- **instrumented-tests** runs on the `pixel8proapi35` Gradle Managed Device with KVM and `swiftshader_indirect`. It has no `needs:` on job 1. Its name, `Instrumented Tests`, is a required status check in the `main` ruleset.
- Both jobs use `setup-gradle`'s default cache mode (writes on `main`, read-only on PRs). The managed-device cache is restored everywhere and saved only on `main`.
- No job scans dependencies for advisories. Dependabot alerts and security updates cover that. Do not add a Trivy `fs` scan: Trivy finds Gradle dependencies only through a committed `gradle.lockfile`, which this repo lacks, so it reports zero findings.

## Gradle flags

- Parallel, build cache, `workers.max=4`, configuration cache and no incremental Kotlin are set through `GRADLE_OPTS` in the workflow. Do not repeat them as `--parallel`, `--build-cache` or `--no-daemon` on `./gradlew` lines.
- Keep the configuration cache out of `release.yml`. That build reads signing secrets at configuration time, so its cache entries would contain the keystore password.

## Workflow hygiene

- Pin every action to a commit SHA with an exact-version comment (`@<sha> # v<x.y.z>`). Dependabot updates both.
- Every checkout sets `persist-credentials: false`. `${{ }}` values reach `run:` scripts only through `env:`.
- Dependabot groups all action bumps into one PR with a 7-day cooldown on both ecosystems, because every merge to `main` publishes a release.
- Workflow changes must pass `actionlint` and `zizmor` clean. The one `zizmor` ignore (`dangerous-triggers` on `release.yml`) is justified inline.
- Validate with `act` before committing. On Apple Silicon add `--container-architecture linux/amd64`.

```bash
act --list
act push -j build-and-test --container-architecture linux/amd64 --dryrun
```
