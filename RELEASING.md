# CI and releases

## Continuous integration

Pushes and pull requests run Python tests, source checks, JVM fault tests, an
Android APK build and an arm64 native build on Ubuntu 24.04. PR jobs have no signing
or vendor-input secrets. The `runtime-ci-only` artifact contains only the test APK,
native helper and JVM test output; it contains neither a private key nor vendor
binaries, and is not a complete installable module.

## Release setup

Create a GitHub environment named `release`. Configure required reviewers if
releases should pause for approval. In that environment, configure:

| Secret | Value |
|---|---|
| `REGION_INPUTS_URL` | HTTPS URL of the input ZIP described below; may be a private signed URL |
| `PANEL_KEYSTORE_B64` | Base64 encoding of the persistent private panel keystore |
| `PANEL_STORE_PASSWORD` | Keystore and key password |
| `PANEL_KEY_ALIAS` | Signing alias, typically `panel` |

Prepare the vendor input archive separately from Git:

```sh
python tools/inputs.py pack --directory build/inputs --archive build/vendor-inputs.zip
```

Only the 25 locked inputs are packed. Upload that archive to the chosen download
location and set `REGION_INPUTS_URL`. It contains vendor code, not account files,
device journals or signing keys. Permission to distribute vendor binaries must be
handled separately from the MIT source license. Fork CI can run without this
archive; publishing a complete module requires it.

## Publishing

1. Update `version.json`, `CHANGELOG.md` and `CHANGELOG_zh.md`; run local tests and
   a complete build with the stable signing key.
2. Review the public source, release body and final attachments. Preserve the
   distinction between host tests and hardware acceptance.
3. Push `v<version>`. Alternatively run the workflow manually with an existing tag;
   the workflow checks out that tag rather than the selected branch.
4. After CI passes, the environment-gated job fetches the exact vendor inputs,
   rebuilds/audits the framework, reruns JVM tests and signs the panel.
5. The ZIP verifier checks every payload file. A separate job with `contents: write`
   creates a draft release with the verified attachments and publishes it only
   after uploading them. Tags containing `-` become prereleases.

Attachments are the complete offline module ZIP, its file manifest, build identity,
MIT license, third-party notices and `SHA256SUMS.txt`. The release body is generated
from the two matching changelog sections. Source archives are supplied by GitHub.
Existing releases are never silently overwritten; a failed upload can leave a
draft that the maintainer must inspect before retrying.

Actions success verifies hosted builds and packaging, not physical installation
or body-tracking behavior. Keep new installation support in prerelease until its
hardware acceptance is complete.
