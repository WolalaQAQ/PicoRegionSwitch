package com.pico4.regionswitch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Exercises first-install ordering and the real device-local journal/profile preservation. */
public final class FirstInstallTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("First-install check " + checks); }
    static void text(File file, String value) throws Exception { file.getParentFile().mkdirs(); Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8)); }
    interface Throwing { void run() throws Exception; }
    static void rejected(Throwing action) throws Exception { boolean rejected = false; try { action.run(); } catch (Exception error) { rejected = true; } check(rejected); }
    static final class Driver implements SwitchTransaction.Actions {
        List<String> calls = new ArrayList<>(); String failure;
        Driver(String failure) { this.failure = failure; }
        public void step(String name) throws Exception { calls.add(name); if (name.equals(failure)) throw new IOException(name); }
    }
    static class Fixture extends Engine {
        final File home; String boot = "boot-before"; List<String> calls = new ArrayList<>();
        JSONObject cn, global;
        int startingFailures;
        Fixture(File home) throws Exception { super(home.toString(), (a, b) -> {}); this.home = home; home.mkdirs(); layoutFile = new File(home, "state/headset/layout.json"); layoutFile.getParentFile().mkdirs(); }
        @Override public String shell(String code) throws Exception {
            calls.add(code);
            if (code.startsWith("cat /proc/sys")) return boot;
            if (code.startsWith("settings get")) return "CN";
            if (code.startsWith("pm path ")) {
                for (int i = 0; i < ROLES.length; i++) if (code.endsWith(PACKAGES[i])) return "package:" + cn.getJSONObject("apks").getJSONObject(ROLES[i]).getString("path");
            }
            if (code.startsWith("find /data/system/package_cache")) return "";
            return "";
        }
        @Override JSONObject inspect() { return new JSONObject().put("sleeping", false).put("boot", boot).put("components", new JSONObject()); }
        @Override JSONArray rowSnapshot(String name) { return new JSONArray(); }
        @Override JSONObject ui() { return new JSONObject().put("locales", "zh-CN").put("timezone", "Asia/Shanghai"); }
        @Override String country() { return "CN-original-bytes"; }
        @Override String accounts() { return "account-identities"; }
        @Override void stop() { calls.add("STOP"); }
        @Override void snapshot() throws Exception { text(new File(run, "app-data-before.tar"), "immutable snapshot"); }
        @Override void install(JSONObject profile) throws Exception { verifyApks(profile, false); calls.add("IDENTICAL_INSTALL"); }
        @Override void resume(JSONObject profile) { calls.add("RESUME"); }
        @Override void verifyBackup() { calls.add("VERIFY_BACKUP"); }
        @Override void healthy(JSONObject profile) throws Exception {
            verifyApks(profile, false); calls.add("HEALTH");
            if (startingFailures-- > 0) { calls.add("WAIT_HEALTH"); throw new IOException("Persistent process missing or restarting"); }
        }
        @Override void verifyRows(JSONArray rows) { calls.add("VERIFY_ROWS"); }
        @Override void current(String name) throws Exception { text(new File(home, "state/current"), name); }
        @Override JSONObject profile(String name) { return name.equals("global") ? global : cn; }
    }
    static final class InstallFixture extends FirstInstall {
        final File home;
        InstallFixture(Fixture engine) { super(engine, new File(engine.home, "state")); home = engine.home; }
        @Override File framework() { return new File(home, "original-services.jar"); }
        @Override File fitness() { return new File(home, "original-fitness.apk"); }
        @Override void verifyOriginalFiles(File framework, File fitness) throws Exception {
            if (!new String(Files.readAllBytes(framework.toPath()), StandardCharsets.UTF_8).equals("original-framework") ||
                !new String(Files.readAllBytes(fitness.toPath()), StandardCharsets.UTF_8).equals("original-fitness")) throw new IOException("Base mismatch");
        }
        @Override void verifyLoadedFiles() throws Exception { if (!((Fixture)engine).boot.equals("boot-after")) throw new IOException("Overlay not loaded"); }
    }
    static InstallFixture fixture(File root, String name) throws Exception {
        File home = new File(root, name); Fixture engine = new Fixture(home);
        JSONObject apks = new JSONObject(), allowed = new JSONObject();
        for (int i = 0; i < Engine.ROLES.length; i++) {
            File apk = new File(home, "original/" + Engine.ROLES[i] + ".apk"); text(apk, "CN-" + String.join("", Collections.nCopies(i + 1, "x")));
            JSONObject item = new JSONObject().put("path", apk.getAbsolutePath()).put("size", apk.length()).put("sha256", Engine.digest(apk)).put("package", Engine.PACKAGES[i]);
            apks.put(Engine.ROLES[i], item); allowed.put(Engine.ROLES[i], new JSONArray().put(new JSONObject(item.toString())));
        }
        engine.cn = new JSONObject().put("apks", apks).put("name", "cn").put("region_value", "CN");
        engine.global = new JSONObject(engine.cn.toString());
        new File(home, "profiles").mkdir(); Engine.write(new File(home, "profiles/initial-cn.json"), allowed);
        text(new File(home, "system/app/PicoRegionSwitch/PicoRegionSwitch.apk"), "pinned-executor");
        text(new File(home, "original-services.jar"), "original-framework"); text(new File(home, "original-fitness.apk"), "original-fitness");
        return new InstallFixture(engine);
    }
    static void ordering() throws Exception {
        Driver happy = new Driver(null); FirstInstallTransaction.prepare(happy);
        check(happy.calls.indexOf("install-snapshot") < happy.calls.indexOf("install-identical-apks"));
        check(happy.calls.indexOf("install-verify-backup") < happy.calls.indexOf("install-support"));
        check(happy.calls.get(happy.calls.size() - 1).equals("install-await-boot"));
        for (String failure : happy.calls) {
            Driver driver = new Driver(failure); rejected(() -> FirstInstallTransaction.prepare(driver));
            if (!failure.equals("install-preflight") && !failure.equals("install-journal")) check(driver.calls.get(driver.calls.size() - 1).equals("install-restore-runtime"));
            check(!driver.calls.contains("activate-data") && !driver.calls.contains("set-target-region"));
        }
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0], "first-install"); root.mkdirs(); ordering();
        InstallFixture install = fixture(root, "happy"); Fixture engine = (Fixture)install.engine;
        FirstInstallTransaction.prepare(install); check(FirstInstall.awaitingBoot(Engine.read(install.journal)));
        check(new File(install.state, "installed-cn.json").isFile());
        JSONObject profile = Engine.read(new File(install.state, "installed-cn.json"));
        for (String role : Engine.ROLES) {
            JSONObject item = profile.getJSONObject("apks").getJSONObject(role);
            check(item.getString("path").contains("first-install-")); check(new File(item.getString("path")).length() == item.getLong("size"));
            check(engine.calls.stream().anyMatch(command -> command.contains("chcon u:object_r:system_file:s0 " + Engine.q(item.getString("path")))));
        }
        String plan = new String(Files.readAllBytes(new File(profile.getString("install_script")).toPath()), StandardCharsets.UTF_8);
        check(plan.contains("Success: streamed $3 bytes")); check(plan.contains("--multi-package")); check(!plan.contains("pm clear"));
        check(plan.indexOf("install-commit") > plan.indexOf("base.apk")); check(plan.indexOf('\1') == -1);
        for (String role : Engine.ROLES) {
            JSONObject item = profile.getJSONObject("apks").getJSONObject(role);
            check(plan.contains("pm_ok install-write -S " + item.getLong("size") + " \"$child\" base.apk " + Engine.q(item.getString("path"))));
        }
        JSONObject pinned = engine.pinProfile(profile, "switch-source");
        engine.verifyApks(pinned, false);
        String pinnedPlan = new String(Files.readAllBytes(new File(pinned.getString("install_script")).toPath()), StandardCharsets.UTF_8);
        check(pinnedPlan.contains("switch-source")); check(!pinnedPlan.contains(new File(engine.run, "cn").getAbsolutePath()));
        Files.write(new File(root, "initial-install.sh").toPath(), plan.getBytes(StandardCharsets.UTF_8));
        rejected(install::finishBoot); check(install.pending());
        engine.boot = "boot-after"; install.finishBoot(); check(!install.pending());
        InstallFixture partial = fixture(root, "partial-support"); FirstInstallTransaction.prepare(partial); partial.checkpoint("support");
        ((Fixture)partial.engine).boot = "boot-after"; partial.finishBoot(); check(!partial.pending());
        InstallFixture badBackup = fixture(root, "bad-backup"); FirstInstallTransaction.prepare(badBackup); ((Fixture)badBackup.engine).boot = "boot-after";
        text(new File(badBackup.engine.run, "app-data-before.tar"), "changed"); rejected(badBackup::finishBoot); check(badBackup.pending());
        InstallFixture rollback = fixture(root, "rollback"); FirstInstallTransaction.prepare(rollback); rollback.rollbackPreparation();
        check(!new File(rollback.state, "current").exists()); check(!new File(rollback.state, "installed-cn.json").exists());
        check(rollback.journal.isFile() && new File(rollback.engine.run, "app-data-before.tar").isFile());
        InstallFixture unsupported = fixture(root, "unsupported"); text(new File(unsupported.home, "original/matrix.apk"), "unknown");
        rejected(() -> FirstInstallTransaction.prepare(unsupported)); check(!unsupported.journal.exists()); check(!((Fixture)unsupported.engine).calls.contains("STOP"));
        InstallFixture busy = fixture(root, "busy"); text(new File(busy.state, "pending.json"), "existing");
        rejected(() -> FirstInstallTransaction.prepare(busy)); check(!busy.journal.exists());
        InstallFixture markers = fixture(root, "partial-markers"); text(new File(markers.state, "framework.json"), "existing");
        rejected(() -> FirstInstallTransaction.prepare(markers)); check(!markers.journal.exists());
        InstallFixture starting = fixture(root, "starting-health"); ((Fixture)starting.engine).startingFailures = 2;
        FirstInstallTransaction.prepare(starting); check(FirstInstall.awaitingBoot(Engine.read(starting.journal)));
        check(Collections.frequency(((Fixture)starting.engine).calls, "WAIT_HEALTH") == 2);
        InstallFixture missing = fixture(root, "missing-health"); missing.healthWaitMillis = 0; ((Fixture)missing.engine).startingFailures = 99;
        rejected(() -> FirstInstallTransaction.prepare(missing)); check(missing.pending());
        check(Collections.frequency(((Fixture)missing.engine).calls, "WAIT_HEALTH") == 1);
        System.out.println("FIRST_INSTALL_CHECKS=" + checks + " RESULT=success");
    }
}
