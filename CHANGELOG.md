# Changelog

## [0.6.0-dev.1] - 2026-11-09
### Features
- Independent MIT-licensed source project for the eight-app offline CN/global switcher.
- Integrate first-install backup, identical-CN APK staging, interrupted-preparation recovery and first-boot verification into the single Magisk ZIP.
- Preserve each supported headset's original CN versions as its return profile; retain separate CN/global data banks and version-pinned switch recovery.
- Add portable builds, host fault tests, locked vendor inputs and tag-triggered CI/Release workflows.
- Set a PMS-readable SELinux label on retained APKs and wait up to 30 seconds for the observed persistent-process startup transition, retaining the full final health checks.
### Design Rationale
- Preserve existing CN code/data compatibility instead of forcing the maintainer's CN app versions onto every headset.
- Reinstall identical CN APKs before loading the Fitness overlay so the first reboot keeps CN code active; verify the new boot before enabling switches.
- Keep vendor originals outside Git and keep release signing credentials out of PR jobs and artifacts.
### Notes & Caveats
- Development prerelease for the audited PICO 4 Pro / CN OS 5.13.7 baseline. Root and Magisk are prerequisites; unsupported initial APK identities are rejected.
- First installation from an uninitialized tool state, pre-boot recovery, first-boot verification, both switching directions, post-resume rollback and overlay removal/startup have hardware acceptance on the supported CN update combination. Other stock APK combinations were not individually installed; a failed boot was not deliberately induced.
- Official APKs retain their signatures and copyright terms. The module does not upgrade the OS or change base regional properties.
