# Building

Use Python 3.11 or newer and JDK 17. Set `JAVA_HOME` to the JDK and `ANDROID_HOME`
to an Android SDK containing:

- `platforms;android-35`
- `build-tools;35.0.0`
- `ndk;26.1.10909125`

Windows x64 and Linux x64 toolchain paths are supported. Run commands from the
repository root. Output directories must be new so previous results are preserved.

## Source tests and runtime build

```sh
python -B -m unittest discover -s tests -v
python tools/dependencies.py
python tools/build.py --output build/runtime
```

The runtime build executes JVM transaction, Engine and first-install fault tests,
compiles the APK and builds the strict arm64 directory-rename helper. Its generated
CI signing key is for testing only. This APK by itself is not an installer.

## Complete offline Magisk ZIP

Provide the 25 official files listed in `config/inputs.lock.json` under a directory
such as `build/inputs`: eight `cn/<role>.apk`, eight `global/<role>.apk`, eight
`stock-cn/<role>.apk`, and the original CN 5.13.7 `services-original.jar`.
`stock-cn` defines accepted initial APK identities; those extra APKs are not copied
into the final ZIP. Global Matrix/Store use 6.3.4/4.9.0; the other six global apps
are the audited official 5.13.8 variants, on the unchanged CN 5.13.7 OS baseline.

The source lock pins the supported bytes, not a latest-version download service.
The firmware is available through PICO's normal distribution channels; the retained
global Matrix/Store references are in the [3-2026 app collection](https://github.com/owomushi/Pico-Region-Switcher-Apps/releases/tag/3-2026).
The build checks all input identities and official platform signatures before use.

```sh
python tools/inputs.py check --directory build/inputs
python tools/dependencies.py --framework
python tools/build.py --output build/module --inputs build/inputs --keystore /path/to/panel.jks --key-alias panel
python tools/verify.py --build build/module
```

Set `PANEL_STORE_PASSWORD` in the environment before building. The key and store
password must match. Use a persistent private signing key for distributable builds
so panel updates retain their signing identity; do not use the generated CI key.
The builder never logs the password.

The framework is rebuilt from the original, with zero-change and patched controls,
full method-semantic comparison and raw ZIP-entry preservation checks. The resulting
framework must match the audited patch identity. The module ZIP has fixed entry
timestamps and a manifest of every file. A valid complete build does not replace
clean-device hardware acceptance of the first-install flow.

## Versioning

`version.json` supplies the APK and module versions. Update both changelogs for a
release. A release tag must be exactly `v<version>`; a suffix such as `-dev.1` creates
a GitHub prerelease.
