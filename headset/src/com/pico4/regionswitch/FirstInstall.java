package com.pico4.regionswitch;

import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Runs from Magisk before the first overlay is loaded; all backups stay on this device. */
public class FirstInstall implements SwitchTransaction.Actions {
    static final String MODULE = "/data/adb/modules/pico-region-downgrade";
    static final String ORIGINAL = "bca28757d42e6198a332db77c024c77e2ec66cf1183fbba8a6c47d0e2d7b0125";
    static final String PATCHED = "222d3f66a9556571b1a6b69f97171f93a090698e301d8e79f1a0aecd273a5102";
    static final String FITNESS = "f26c3acf526410bc0a11076ddfa0f7bab1deb6fd7a5ec9512ecfe0b5aa008f7c";
    final Engine engine;
    final File state;
    final File journal;
    JSONObject saved;
    JSONObject original;
    long healthWaitMillis = 30000;
    FirstInstall(Engine engine) { this(engine, new File(Engine.STATE)); }
    FirstInstall(Engine engine, File state) { this.engine = engine; this.state = state; this.journal = new File(state, "first-install.json"); }
    File framework() { return new File("/system/framework/services.jar"); }
    File fitness() { return new File("/system/priv-app/pvrfit/pvrfit.apk"); }
    void verifyOriginalFiles(File framework, File fitness) throws Exception {
        if (!Engine.digest(framework).equals(ORIGINAL) || !Engine.digest(fitness).equals(FITNESS))
            throw new IOException("Initial installation requires the audited original CN 5.13.7 framework and Fitness base");
    }
    void verifyLoadedFiles() throws Exception {
        if (!Engine.digest(framework()).equals(PATCHED) ||
            !Engine.digest(fitness()).equals(engine.profile("global").getJSONObject("apks").getJSONObject("fitness").getString("sha256")))
            throw new IOException("首次加载的框架／底包不匹配，请保留安装记录。");
    }

    static boolean awaitingBoot(JSONObject value) { return value.optString("phase").equals("awaiting-boot"); }
    public boolean pending() throws Exception { return journal.isFile() && !Engine.read(journal).optString("phase").equals("complete"); }
    void checkpoint(String phase) throws Exception { saved.put("phase", phase); Engine.write(journal, saved); }
    String boot() throws Exception { return engine.shell("cat /proc/sys/kernel/random/boot_id").trim(); }
    void idle() throws Exception {
        if (!engine.shell("pidof VirtualDesktop.Android || true").trim().isEmpty()) throw new IOException("请先退出 Virtual Desktop，再安装模块。");
        for (String name : new String[]{"pending.json", "headset-pending.json", "headset-busy", "guard-current"})
            if (new File(state, name).exists()) throw new IOException("Existing transaction needs recovery: " + name);
    }
    void preflight() throws Exception {
        idle();
        if (journal.exists() || new File(state, "current").exists()) throw new IOException("Existing installation: recover or update it; do not initialize again");
        for (String marker : new String[]{"framework.json", "fitness-base.json", "fitness-cache.json", "installed-cn.json"})
            if (new File(state, marker).exists()) throw new IOException("Incomplete previous installation: " + marker);
        verifyOriginalFiles(framework(), fitness());
        if (!engine.shell("settings get global user_settings_initialized").trim().equals("CN"))
            throw new IOException("First installation starts in CN; existing mixed/global installations need explicit migration");
        JSONObject allowed = Engine.read(new File(engine.module, "profiles/initial-cn.json"));
        JSONObject apks = new JSONObject();
        for (int i = 0; i < Engine.ROLES.length; i++) {
            String path = engine.shell("pm path " + Engine.PACKAGES[i]).trim().replaceFirst("^package:", "");
            if (path.contains("\n") || !new File(path).isAbsolute()) throw new IOException("Expected one standalone APK");
            String digest = Engine.digest(new File(path));
            JSONArray choices = allowed.getJSONArray(Engine.ROLES[i]); JSONObject found = null;
            for (int j = 0; j < choices.length(); j++) if (choices.getJSONObject(j).getString("sha256").equals(digest)) found = new JSONObject(choices.getJSONObject(j).toString());
            if (found == null || !found.getString("package").equals(Engine.PACKAGES[i]))
                throw new IOException("Unsupported initial CN APK: " + Engine.ROLES[i] + "; no application data changed");
            found.put("path", path); apks.put(Engine.ROLES[i], found);
        }
        for (String root : Engine.BANK) if (!engine.shell("find " + Engine.q(root) + " -mindepth 1 -maxdepth 1 2>/dev/null || true").trim().isEmpty())
            throw new IOException("Existing regional banks found; do not reinitialize them");
        original = new JSONObject().put("name", "cn").put("schema", 1).put("region_value", "CN").put("apks", apks);
        engine.inventory = engine.inspect(); engine.rows = engine.rowSnapshot(null);
    }
    void journal() throws Exception {
        engine.run = new File(state, "headset/first-install-" + UUID.randomUUID());
        if (!engine.run.mkdirs()) throw new IOException("Cannot create installation backup");
        File source = new File(engine.run, "cn"); source.mkdir();
        for (String role : Engine.ROLES) {
            JSONObject apk = original.getJSONObject("apks").getJSONObject(role); File target = new File(source, role + ".apk");
            Files.copy(new File(apk.getString("path")).toPath(), target.toPath());
            if (!Engine.digest(target).equals(apk.getString("sha256"))) throw new IOException("Original APK backup changed");
            // pm checks system_server's SELinux read access even when root supplies the FD.
            // Change only this verified APK, not the private journal or data-bank directories.
            engine.shell("set -e; chmod 600 " + Engine.q(target.toString()) + "; chcon u:object_r:system_file:s0 " + Engine.q(target.toString()));
            apk.put("path", target.getAbsolutePath());
        }
        File script = new File(engine.run, "install-cn.sh");
        Files.write(script.toPath(), InstallPlan.create(original).getBytes(StandardCharsets.UTF_8));
        original.put("install_script", script.getAbsolutePath()).put("install_sha256", Engine.digest(script));
        File executor = new File(engine.run, "executor.apk");
        Files.copy(new File(engine.module, "system/app/PicoRegionSwitch/PicoRegionSwitch.apk").toPath(), executor.toPath());
        saved = new JSONObject().put("schema", 1).put("phase", "journal").put("before_boot", boot()).put("run", engine.run.getAbsolutePath())
            .put("source_profile", original).put("inventory", engine.inventory).put("rows", engine.rows)
            .put("executor", executor.getAbsolutePath()).put("executor_sha256", Engine.digest(executor)).put("caches", new JSONArray());
        engine.pending = saved;
        saved.put("ui", engine.ui()).put("country", engine.country()).put("accounts", engine.accounts());
        Files.copy(framework().toPath(), new File(engine.run, "original-services.jar").toPath());
        Files.copy(fitness().toPath(), new File(engine.run, "original-fitness.apk").toPath());
        verifyOriginalFiles(new File(engine.run, "original-services.jar"), new File(engine.run, "original-fitness.apk"));
        Engine.write(journal, saved);
    }
    void load() throws Exception {
        saved = Engine.read(journal); engine.run = new File(saved.getString("run")); original = saved.getJSONObject("source_profile");
        if (!Engine.digest(new File(saved.getString("executor"))).equals(saved.getString("executor_sha256"))) throw new IOException("Installation recovery executor differs");
        engine.pending = saved; engine.inventory = saved.getJSONObject("inventory"); engine.rows = saved.getJSONArray("rows");
        engine.verifyApks(original, false);
    }
    void invalidateCache() throws Exception {
        String listing = engine.shell("find /data/system/package_cache -mindepth 2 -maxdepth 2 -type f -name pvrfit-16");
        JSONArray caches = saved.getJSONArray("caches");
        for (String path : listing.split("\n")) {
            if (path.isEmpty()) continue;
            if (!path.matches("/data/system/package_cache/[a-zA-Z0-9_-]+/pvrfit-16")) throw new IOException("Unexpected parser cache path");
            File live = new File(path), backup = new File(engine.run, "cache-" + caches.length() + ".bin");
            Files.copy(live.toPath(), backup.toPath());
            if (!Engine.digest(backup).equals(Engine.digest(live))) throw new IOException("Parser cache changed while being backed up");
            String[] metadata = engine.shell("stat -c '%u %g %a' " + Engine.q(path) + "; ls -Zd " + Engine.q(path)).trim().split("\n");
            String[] fields = metadata[0].split(" ");
            caches.put(new JSONObject().put("path", path).put("backup", backup.getAbsolutePath()).put("sha256", Engine.digest(backup))
                .put("uid", fields[0]).put("gid", fields[1]).put("mode", fields[2]).put("label", metadata[1].split(" ")[0]));
            Engine.write(journal, saved); Files.delete(live.toPath());
        }
    }
    void support() throws Exception {
        checkpoint("support"); invalidateCache();
        JSONObject global = engine.profile("global"); String fitness = global.getJSONObject("apks").getJSONObject("fitness").getString("sha256");
        Engine.write(new File(state, "framework.json"), new JSONObject().put("schema", 1).put("sha256", PATCHED).put("original_sha256", ORIGINAL).put("module", MODULE).put("installed_boot", saved.getString("before_boot")));
        Engine.write(new File(state, "fitness-base.json"), new JSONObject().put("schema", 1).put("original_sha256", FITNESS)
            .put("apk", global.getJSONObject("apks").getJSONObject("fitness")).put("installed_boot", saved.getString("before_boot"))
            .put("module", MODULE).put("system_path", "/system/priv-app/pvrfit/pvrfit.apk").put("module_path", MODULE + "/system/priv-app/pvrfit/pvrfit.apk"));
        Engine.write(new File(state, "fitness-cache.json"), new JSONObject().put("prepared", true).put("base_sha256", fitness));
        Engine.write(new File(state, "installed-cn.json"), original);
        engine.current("cn");
        Engine.write(engine.layoutFile, new JSONObject().put("components", 8).put("current", "cn").put("profile", original));
    }
    void verifySourceHealth() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + healthWaitMillis;
        while (true) {
            try { engine.healthy(original); return; }
            catch (IOException starting) {
                if (!"Persistent process missing or restarting".equals(starting.getMessage()) || SystemClock.elapsedRealtime() >= deadline) throw starting;
                SystemClock.sleep(500);
            }
        }
    }
    public void finishBoot() throws Exception {
        load();
        if (!Arrays.asList("install-support", "support", "install-await-boot", "awaiting-boot").contains(saved.optString("phase")))
            throw new IOException("首次安装中断，请恢复安装准备；当前不能切区。");
        if (boot().equals(saved.getString("before_boot"))) throw new IOException("安装准备完成，请重启一次加载模块。");
        verifyLoadedFiles();
        if (!Engine.digest(new File(engine.run, "app-data-before.tar")).equals(saved.getString("backup_sha256"))) throw new IOException("Installation data backup differs");
        engine.verifyBackup(); engine.verifyRows(engine.rows); verifySourceHealth();
        if (!awaitingBoot(saved)) support();
        checkpoint("complete");
    }
    public void rollbackPreparation() throws Exception {
        load();
        verifyOriginalFiles(framework(), fitness());
        engine.verifyApks(original, true); engine.verifyRows(engine.rows); engine.resume(original); verifySourceHealth();
        JSONArray caches = saved.getJSONArray("caches");
        for (int i = 0; i < caches.length(); i++) {
            JSONObject item = caches.getJSONObject(i); File backup = new File(item.getString("backup")), live = new File(item.getString("path"));
            if (!Engine.digest(backup).equals(item.getString("sha256"))) throw new IOException("Saved parser cache differs");
            // A newly rebuilt cache must not be overwritten; removal itself is reversible by PMS.
            if (!live.exists()) {
                Files.copy(backup.toPath(), live.toPath());
                engine.shell("set -e; chown " + item.getString("uid") + ":" + item.getString("gid") + " " + Engine.q(live.toString()) + "; chmod " + item.getString("mode") + " " + Engine.q(live.toString()) + "; chcon " + Engine.q(item.getString("label")) + " " + Engine.q(live.toString()));
            }
        }
        for (String name : new String[]{"framework.json", "fitness-base.json", "fitness-cache.json", "installed-cn.json", "current"}) Files.deleteIfExists(new File(state, name).toPath());
        Files.deleteIfExists(engine.layoutFile.toPath()); checkpoint("preparation-restored");
        // Preserve the journal and every backup; a deliberate reinstall must archive this record first.
    }
    @Override public void step(String phase) throws Exception {
        if (saved != null && !phase.equals("install-restore-runtime")) checkpoint(phase);
        switch (phase) {
            case "install-preflight": preflight(); break;
            case "install-journal": journal(); break;
            case "install-stop": engine.stop(); break;
            case "install-snapshot": engine.snapshot(); break;
            case "install-identical-apks": engine.install(original); break;
            case "install-resume": engine.resume(original); break;
            case "install-verify-backup": engine.verifyBackup(); saved.put("backup_sha256", Engine.digest(new File(engine.run, "app-data-before.tar"))); Engine.write(journal, saved); break;
            case "install-verify-source": engine.verifyRows(engine.rows); verifySourceHealth(); break;
            case "install-support": support(); break;
            case "install-await-boot": checkpoint("awaiting-boot"); break;
            case "install-restore-runtime": engine.resume(original); break;
            default: throw new IllegalArgumentException(phase);
        }
    }
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0 || args.length != 2) throw new SecurityException("Usage (root): --prepare|--restore-preparation MODULE");
        FirstInstall install = new FirstInstall(new Engine(args[1], (phase, message) -> System.out.println(phase)));
        if (args[0].equals("--prepare")) {
            if (install.journal.isFile()) {
                JSONObject previous = Engine.read(install.journal);
                if (previous.optString("phase").equals("preparation-restored"))
                    Files.move(install.journal.toPath(), new File(previous.getString("run"), "restored-first-install.json").toPath());
            }
            FirstInstallTransaction.prepare(install);
        }
        else if (args[0].equals("--check-update")) {
            if (install.pending()) throw new IOException("Finish or restore the interrupted first installation before updating");
        }
        else if (args[0].equals("--restore-preparation")) {
            JSONObject value = Engine.read(install.journal);
            if (!Engine.digest(new File(System.getenv("CLASSPATH"))).equals(value.getString("executor_sha256"))) throw new IOException("Use the saved installation executor for recovery");
            install.rollbackPreparation();
        }
        else throw new IllegalArgumentException(args[0]);
        System.out.println("FIRST_INSTALL_SUCCESS");
    }
}
