# CI and Releases

**English** | [简体中文](RELEASING_zh.md)

This repository uses [`.github/workflows/ci.yml`](.github/workflows/ci.yml) to run unprivileged verification on every push and pull request, and to build and publish signed offline Magisk releases only from version tags.

## Continuous Integration

Every push and pull request runs the `test` job on `ubuntu-24.04`:

1. Runs Python unit tests and source contract checks (`tests/`).
2. Fetches pinned host-test dependencies (`tools/dependencies.py`).
3. Runs JVM fault tests, compiles the test panel APK, and builds the arm64 `region-rename` binary.
4. Uploads the `runtime-ci-only` artifact (`PicoRegionSwitch.apk`, `region-rename`, and `host-tests.txt`).

Pull request jobs never receive signing keys or vendor-input secrets. `runtime-ci-only` is strictly for CI inspection and is not a complete installable module.

---

## One-Time Release Environment Setup

Create a GitHub repository environment named `release` (optionally add required reviewers so releases pause for manual approval) and configure these four secrets:

| Secret | Description |
|---|---|
| `REGION_INPUTS_URL` | HTTPS URL hosting the packed vendor-inputs ZIP (may be a private signed URL) |
| `PANEL_KEYSTORE_B64` | Base64-encoded persistent private `.jks` keystore for signing `PicoRegionSwitch.apk` |
| `PANEL_STORE_PASSWORD` | Keystore and key password |
| `PANEL_KEY_ALIAS` | Signing key alias (typically `panel`) |

### Packing the Vendor Inputs Archive

Pack the 25 locked official inputs outside Git and upload the archive to the HTTPS URL referenced by `REGION_INPUTS_URL`:

```sh
python tools/inputs.py pack --directory build/inputs --archive build/vendor-inputs.zip
```

`inputs.py pack` validates every file against [`config/inputs.lock.json`](config/inputs.lock.json) and stores only those 25 files—no account data, device journals, or signing keys. Forks can run CI without this archive; building a complete release module requires it. Distribution rights for vendor binaries remain separate from this repository's MIT license.

---

## Publishing a Release

1. Update [`version.json`](version.json), [`CHANGELOG.md`](CHANGELOG.md), and [`CHANGELOG_zh.md`](CHANGELOG_zh.md). Run local tests and a local complete build with your persistent signing key.
2. Push tag `v<version>` (or run the workflow manually via `workflow_dispatch` with an existing tag; the workflow always checks out `refs/tags/<tag>` rather than a branch head).
3. After `test` passes, the environment-gated `module` job:
   - Verifies the tag matches `version.json` and both changelogs.
   - Fetches and validates `REGION_INPUTS_URL`, rebuilds and audits `services.jar`, reruns JVM tests, and signs the panel with `PANEL_KEYSTORE_B64` (removing the temporary keystore in an `always()` cleanup step).
   - Verifies every payload file via `tools/verify.py` and stages release assets via `tools/release.py`.
4. The separate `publish` job (the only job granted `contents: write`) creates a **draft** GitHub Release, uploads all verified attachments, and publishes the release only after uploads succeed.

### Release Attachments & Safeguards

| File | Contents |
|---|---|
| `PicoRegionSwitch-<version>.zip` | Complete offline installable Magisk module |
| `module-manifest.json` | File list and SHA-256 manifest of the module payload |
| `build-result.json` | Build identity and output digests |
| `LICENSE` | MIT license for original source |
| `THIRD_PARTY_NOTICES.md` | Notices for vendor files and build dependencies |
| `SHA256SUMS.txt` | SHA-256 checksums for all attachments |

- Release notes are assembled automatically from the matching `[<version>]` sections of `CHANGELOG.md` and `CHANGELOG_zh.md`.
- Tags containing `-` (for example, `v0.6.0-dev.1`) are marked as GitHub prereleases. Keep new installation paths in prerelease until physical headset acceptance is complete.
- Existing releases are never overwritten automatically; if an upload fails partway, inspect and clean up the leftover draft release before retrying.
