package com.pico4.regionswitch;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exercises the real Engine, not a second implementation of its bank or pinning logic. */
public final class EngineHostTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Engine check " + checks); }
    static String path(File file) { return file.getAbsolutePath().replace('\\', '/'); }
    static void text(File file, String value) throws Exception { Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8)); }
    static final class Fixture extends Engine {
        final File home;
        final List<String> calls = new ArrayList<>();
        String script;
        Fixture(File home) { super(path(home), (phase, message) -> {}); this.home = home; run = new File(home, "saved-run"); }
        @Override public String shell(String code) { calls.add(code); if (code.startsWith("dumpsys package")) return "  userId=10087\n  User 0: installed=true enabled=0\n"; if (code.equals("dumpsys power")) return "mWakefulness=Asleep"; return "1\n"; }
        @Override String metadata(String name) { return "10087 10087 700 77\nu:object_r:app_data_file:s0 " + name; }
        @Override void bankScript(String value) { script = "set -eu\n" + value; }
        @Override String bank(JSONObject row, String name) throws Exception { return path(new File(home, "banks/" + name + "/" + row.getInt("kind") + "/" + row.getString("package"))); }
        @Override void freeze() { calls.add("FREEZE"); }
        @Override void release() { calls.add("RELEASE"); }
        @Override void current(String name) { calls.add("CURRENT=" + name); }
        @Override void swiftBinding(boolean connect) { calls.add("SWIFT_BIND=" + connect); }
        @Override public String shell(String code, long timeout, int... accepted) { calls.add(code); return "REGION_ATOMIC_INSTALL_SUCCESS"; }
        @Override void verifyApks(JSONObject value, boolean installed) throws Exception {
            if (installed) calls.add("VERIFY_INSTALLED"); else super.verifyApks(value, false);
        }
    }
    static JSONObject row(File home, String role, int kind, boolean exists) throws Exception {
        int index = Arrays.asList(Engine.ROLES).indexOf(role);
        JSONObject value = new JSONObject().put("role", role).put("package", Engine.PACKAGES[index]).put("kind", kind)
            .put("path", path(new File(home, "live/" + kind + "/" + Engine.PACKAGES[index]))).put("exists", exists);
        if (exists) value.put("uid", "10087").put("gid", "10087").put("mode", "700").put("inode", "77").put("label", "u:object_r:app_data_file:s0");
        return value;
    }
    static void bankFixture(File root, String name, boolean absent) throws Exception {
        File home = new File(root, name); home.mkdirs(); Fixture engine = new Fixture(home);
        engine.sourceName = "global"; engine.targetName = "global"; engine.upgrading = true;
        engine.rows = new JSONArray();
        if (!absent) engine.rows.put(row(home, "matrix", 0, true));
        engine.rows.put(row(home, "swift", absent ? 2 : 0, !absent));
        engine.targetRows = new JSONArray(engine.rows.toString()); engine.configureRows();
        Engine.write(new File(home, "rows.json"), new JSONObject().put("rows", engine.rows));
        engine.activate(); text(new File(home, "activate.sh"), engine.script);
        engine.park(); text(new File(home, "park.sh"), engine.script);
        engine.restore(); text(new File(home, "restore.sh"), engine.script);
        check(engine.rows.getJSONObject(engine.rows.length() - 1).getString("source_bank").equals("cn"));
        check(!engine.script.contains("com.bytedance.pico.matrix"));
    }
    static void loops(File root) throws Exception {
        Fixture engine = new Fixture(root);
        check(Engine.ROLES.length == 8 && Engine.PACKAGES[7].equals("com.pvr.swift"));
        engine.rows = engine.rowSnapshot(null); engine.targetRows = engine.rowSnapshot("global");
        check(engine.rows.length() == 24);
        engine.sourceName = "global"; engine.targetName = "global"; engine.upgrading = true; engine.configureRows();
        int moving = 0;
        for (int i = 0; i < engine.rows.length(); i++) if (engine.rows.getJSONObject(i).getBoolean("move")) moving++;
        check(moving == 3);
        engine.sourceName = "cn"; engine.targetName = "cn"; engine.configureRows();
        for (int i = 0; i < engine.rows.length(); i++) check(!engine.rows.getJSONObject(i).getBoolean("move"));
        engine.upgrading = false; engine.targetName = "global"; engine.configureRows();
        for (int i = 0; i < engine.rows.length(); i++) check(engine.rows.getJSONObject(i).getBoolean("move"));
        engine.inventory = engine.inspect(); check(engine.inventory.getJSONObject("components").length() == 8);
        engine.calls.clear(); engine.stop(); check(engine.calls.stream().anyMatch(code -> code.contains("disable-user --user 0 com.pvr.swift")));
        check(engine.calls.get(0).equals("SWIFT_BIND=false"));
        JSONObject apks = new JSONObject();
        for (String role : Engine.ROLES) apks.put(role, new JSONObject().put("services", new JSONArray()));
        engine.resume(new JSONObject().put("apks", apks));
        check(engine.calls.contains("SWIFT_BIND=true"));
        check(engine.calls.stream().anyMatch(code -> code.equals("dumpsys activity services com.pvr.swift")));
    }
    static void pinned(File root) throws Exception {
        File home = new File(root, "pin-test"); home.mkdirs(); Fixture engine = new Fixture(home); engine.run.mkdirs();
        File originals = new File(home, "originals"); originals.mkdir(); JSONObject apks = new JSONObject();
        StringBuilder install = new StringBuilder("#!/system/bin/sh\n");
        for (int i = 0; i < Engine.ROLES.length; i++) {
            File file = new File(originals, Engine.ROLES[i] + ".apk"); text(file, "verified-old-" + i);
            apks.put(Engine.ROLES[i], new JSONObject().put("path", path(file)).put("package", Engine.PACKAGES[i]).put("sha256", Engine.digest(file)));
            install.append("cat /data/adb/modules/pico-region-downgrade/packages/global/").append(Engine.ROLES[i]).append(".apk\n");
        }
        File template = new File(home, "template.sh"); text(template, install.toString());
        JSONObject profile = new JSONObject().put("name", "global").put("apks", apks).put("install_script", path(template));
        JSONObject source = engine.pinProfile(profile, "source"), target = engine.pinProfile(profile, "target");
        for (File file : originals.listFiles()) { Files.delete(file.toPath()); text(file, "new-module-generation"); }
        engine.verifyApks(source, false); engine.verifyApks(target, false); checks += 16;
        check(!new String(Files.readAllBytes(new File(source.getString("install_script")).toPath()), StandardCharsets.UTF_8).contains("/data/adb/modules/"));
        File executor = new File(home, "executor.apk"); text(executor, "old-executor");
        engine.pendingFile = new File(home, "pending.json");
        JSONObject pending = new JSONObject().put("schema", 2).put("run", engine.run.getAbsolutePath()).put("inventory", new JSONObject().put("sleeping", false))
            .put("source", "global").put("target", "cn").put("source_profile", source).put("target_profile", target).put("rows", new JSONArray())
            .put("upgrading", false).put("executor", path(executor)).put("executor_sha256", Engine.digest(executor));
        Engine.write(engine.pendingFile, pending); engine.loadPending();
        check(engine.source.getJSONObject("apks").getJSONObject("swift").getString("path").contains("source"));
        engine.calls.clear(); engine.install(source);
        check(engine.calls.get(0).equals("FREEZE"));
        check(engine.calls.get(1).startsWith("setprop "));
        check(engine.calls.stream().anyMatch(code -> code.contains("install-source.sh")));
        File script = new File(source.getString("install_script")); text(script, "changed-script"); engine.calls.clear();
        boolean rejected = false; try { engine.install(source); } catch (IOException expected) { rejected = true; }
        check(rejected && engine.calls.isEmpty());
        text(executor, "wrong-executor"); rejected = false;
        try { engine.loadPending(); } catch (IOException expected) { rejected = true; }
        check(rejected);
        pending.remove("schema"); Engine.write(engine.pendingFile, pending); rejected = false;
        try { engine.loadPending(); } catch (IOException expected) { rejected = true; }
        check(rejected);
    }
    static void layoutRollback(File root) throws Exception {
        File home = new File(root, "layout-test"); home.mkdirs(); Fixture engine = new Fixture(home); engine.run.mkdirs();
        engine.layoutFile = new File(home, "layout.json"); engine.pendingFile = new File(home, "pending.json");
        engine.sourceName = engine.targetName = "global"; engine.target = new JSONObject().put("name", "global");
        engine.pending = new JSONObject().put("source_layout", JSONObject.NULL);
        check(engine.needsUpgrade()); engine.step("current-target"); check(!engine.needsUpgrade());
        engine.step("current-source"); check(engine.needsUpgrade());
        JSONObject old = new JSONObject().put("components", 8).put("current", "cn");
        engine.pending.put("source_layout", old); engine.step("current-target"); engine.step("current-source");
        check(Engine.read(engine.layoutFile).toString().equals(old.toString()));
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]); root.mkdirs(); loops(root); pinned(root); layoutRollback(root);
        check(Engine.regionalProcess("com.pvr.swift.service")); check(Engine.regionalProcess("com.pvr.swift:push"));
        check(!Engine.regionalProcess("com.pvr.swift.service.other")); check(!Engine.regionalProcess("com.pvr"));
        Fixture process = new Fixture(root); check(process.quiescence().contains("|com.pvr.swift.service|com.pvr.pvrfit.ui)"));
        check(process.signals(new JSONArray(), "CONT").trim().equals(":"));
        text(new File(root, "swift-start.sh"), Engine.serviceStart("com.pvr.swift/.service.SwiftService"));
        bankFixture(root, "upgrade", false); bankFixture(root, "partial", false); bankFixture(root, "collision", false); bankFixture(root, "absent", true);
        System.out.println("ENGINE_CHECKS=" + checks + " RESULT=success");
    }
}
