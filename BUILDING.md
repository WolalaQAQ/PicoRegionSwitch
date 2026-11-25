# Building

**English** | [简体中文](BUILDING_zh.md)

This guide covers running source and host tests, building the runtime panel/helper binaries, and assembling the complete offline Magisk ZIP.

## Prerequisites

Windows x64 and Linux x64 toolchains are both supported. Run all commands from the repository root and pass a **new** `--output` directory for each build so previous artifacts are preserved.

| Tool | Version / Components | Environment Variable |
|---|---|---|
| Python | 3.11+ | — |
| JDK | 17 | `JAVA_HOME` |
| Android SDK | `platforms;android-35`, `build-tools;35.0.0`, `ndk;26.1.10909125` | `ANDROID_HOME` |

---

## Source Tests and Runtime Build

Use this workflow for development or CI verification without official vendor binaries:

```sh
python -B -m unittest discover -s tests -v
python tools/dependencies.py
python tools/build.py --output build/runtime
```

`tools/build.py` runs the JVM transaction, `Engine`, and `FirstInstall` fault tests, compiles `PicoRegionSwitch.apk`, and cross-compiles the arm64 `region-rename` native helper.

> [!NOTE]
> Without `--inputs`, `tools/build.py` generates a temporary CI keystore for testing only. The standalone `PicoRegionSwitch.apk` is not a complete installable module.

---

## Complete Offline Magisk ZIP

Building an installable module requires the 25 official files locked in [`config/inputs.lock.json`](config/inputs.lock.json), placed under an input directory such as `build/inputs`:

```text
build/inputs/
├── cn/           # 8 CN APKs (matrix, store, user_center, fitness, friends, home, avatar, swift)
├── global/       # 8 Global APKs (Matrix 6.3.4, Store 4.9.0, plus 6 audited 5.13.8 global APKs)
├── stock-cn/     # 8 stock OS 5.13.7 CN APKs (identity baseline only; not packed into the ZIP)
└── services-original.jar   # Stock CN 5.13.7 /system/framework/services.jar
```

The lockfile pins exact SHA-256 digests and sizes rather than pulling live downloads. Official firmware comes from PICO's normal distribution channels; the retained global Matrix `6.3.4` and Store `4.9.0` references are archived in the [3-2026 app collection](https://github.com/owomushi/Pico-Region-Switcher-Apps/releases/tag/3-2026). Every input file is verified for digest and official platform signer before use.

### Build Steps

1. Set your signing password in the environment (the store password and key password must match; `tools/build.py` never prints it):
   ```sh
   export PANEL_STORE_PASSWORD="your-keystore-password"
   ```
   Use a **persistent private keystore** for any distributable build so panel updates keep the same signing certificate.
2. Validate inputs, fetch framework build dependencies, build the module, and run the package verifier:
   ```sh
   python tools/inputs.py check --directory build/inputs
   python tools/dependencies.py --framework
   python tools/build.py --output build/module --inputs build/inputs --keystore /path/to/panel.jks --key-alias panel
   python tools/verify.py --build build/module
   ```

During the build, `services.jar` is reconstructed from `services-original.jar` with zero-change and patched controls, method-semantic checks, and raw ZIP-entry preservation checks; the final framework must match the audited patch digest. The output Magisk ZIP uses deterministic timestamps and writes a full `module-manifest.json` alongside `build-result.json`.

---

## Versioning

- Edit [`version.json`](version.json) (`version` and `version_code`) to bump the APK and module version.
- Add matching `[<version>]` sections to both [`CHANGELOG.md`](CHANGELOG.md) and [`CHANGELOG_zh.md`](CHANGELOG_zh.md) before tagging.
- Release tags must match `v<version>` exactly. Any tag containing a hyphen (such as `v0.7.0-dev.1`) is published as a GitHub prerelease.
