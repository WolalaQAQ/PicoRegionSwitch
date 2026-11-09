package com.pico4.regionswitch;

/** First installation never changes regions or renames application data. */
public final class FirstInstallTransaction {
    public static void prepare(SwitchTransaction.Actions actions) throws Exception {
        actions.step("install-preflight");
        actions.step("install-journal");
        try {
            actions.step("install-stop");
            actions.step("install-snapshot");
            // Pin the identical CN APKs as data updates before changing the visible Fitness base.
            actions.step("install-identical-apks");
            actions.step("install-resume");
            actions.step("install-verify-backup");
            actions.step("install-verify-source");
            actions.step("install-support");
            actions.step("install-await-boot");
        } catch (Exception failure) {
            // Do not replay APK installs, infer a region, or discard a partial support journal.
            actions.step("install-restore-runtime");
            throw failure;
        }
    }
    private FirstInstallTransaction() {}
}
