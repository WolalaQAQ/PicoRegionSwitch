# Third-party components

The MIT license covers this project's original source code. PICO APKs and firmware
are owned by their respective copyright holders and are not relicensed by this
project. The build uses byte-identical, platform-signed APKs and a patch derived
from the supported firmware's `services.jar`.

Build-time dependencies, not embedded in the panel:

| Component | Version | License | Source |
|---|---|---|---|
| JSON-java | 20240303 | Public domain | https://github.com/stleary/JSON-java |
| JADX / smali / dexlib2 | 1.5.1 distribution | Apache-2.0 / BSD-3-Clause; see upstream notices | https://github.com/skylot/jadx |
| Android SDK / NDK | API 35 / build-tools 35.0.0 / NDK 26.1.10909125 | Android SDK and upstream component terms | https://developer.android.com/studio/terms |

The Android system supplies `org.json` at runtime. The native directory-rename
helper is built from this project's C source against the NDK's Android libraries.
