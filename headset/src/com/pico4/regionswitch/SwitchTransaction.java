package com.pico4.regionswitch;

/** The transaction ordering is shared by the Android worker and host fault tests. */
public final class SwitchTransaction {
    public interface Actions {
        void step(String name) throws Exception;
    }

    public static void run(Actions actions) throws Exception {
        boolean journal = false, stopped = false, moved = false, recovered = false, complete = false;
        try {
            actions.step("preflight");
            actions.step("journal");
            journal = true;
            stopped = true;
            actions.step("stop-source");
            actions.step("snapshot");
            moved = true;
            actions.step("activate-data");
            actions.step("set-target-region");
            actions.step("install-target");
            actions.step("verify-target-data");
            actions.step("resume-target");
            stopped = false;
            actions.step("verify-backup");
            actions.step("verify-target-health");
            actions.step("current-target");
            actions.step("complete");
            actions.step("clear-journal");
            journal = false;
            complete = true;
        } catch (Exception failure) {
            if (moved) {
                // Never run source code on a saved target schema, including after target resume.
                recover(actions);
                recovered = true;
                stopped = false;
            } else if (stopped) {
                actions.step("resume-source");
                stopped = false;
                recovered = true;
            } else {
                recovered = true;
            }
            throw failure;
        } finally {
            // A failed recovery deliberately leaves both journal and freeze protection intact.
            if (journal && recovered) actions.step("clear-journal");
            if (complete || recovered || !journal) actions.step("finish");
        }
    }

    public static void recover(Actions actions) throws Exception {
        actions.step("rollback-start");
        actions.step("stop-target");
        actions.step("park-target");
        actions.step("set-source-region");
        actions.step("install-source-scratch");
        actions.step("stop-source-again");
        actions.step("restore-source-data");
        actions.step("install-source-restored");
        actions.step("verify-source-data");
        actions.step("resume-source");
        actions.step("verify-source-health");
        actions.step("current-source");
    }
}
