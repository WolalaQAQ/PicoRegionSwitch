package com.pico4.regionswitch;

import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Root-only local implementation of the already-verified PC transaction. */
public class Engine implements SwitchTransaction.Actions {
    public static final String STATE = "/data/adb/pico-region-switch";
    public static final String APP = "com.pico4.regionswitch";
    public static final String ASH = "/data/adb/magisk/busybox sh";
    public static final String RENAME = STATE + "/region-rename";
    public static final String GATE = "sys.pico_region_downgrade_until";
    public static final String APK = "/system/app/PicoRegionSwitch/PicoRegionSwitch.apk";
    public static final String[] ROLES = {"matrix", "store", "user_center", "fitness", "friends", "home", "avatar", "swift"};
    public static final String[] PACKAGES = {"com.bytedance.pico.matrix", "com.picovr.store", "com.picovr.vrusercenter",
        "com.pvr.pvrfit", "com.picopui.im", "com.pvr.home", "com.pvr.avatareditor", "com.pvr.swift"};
    public static final String[] PROCESS_ALIASES = {"com.pvr.swift.service", "com.pvr.pvrfit.ui"};
    public static final String[] KINDS = {"ce", "de", "external"};
    public static final String[] LIVE = {"/data/user/0", "/data/user_de/0", "/data/media/0/Android/data"};
    public static final String[] BANK = {"/data/misc_ce/0/pico-region-switch-banks", "/data/misc_de/0/pico-region-switch-banks", "/data/media/0/.pico-region-switch-banks"};
    public static final String[] CORE = {"system_server", "pvrtrackingservice", "com.pvr"};
    public interface Progress { void update(String phase, String message); }
    final String module;
    final Progress progress;
    File run, pendingFile = new File(STATE, "headset-pending.json");
    File layoutFile = new File(STATE, "headset/layout.json");
    JSONObject pending, inventory, source, target;
    JSONArray rows, targetRows, records = new JSONArray();
    String sourceName, targetName, guard;
    boolean holdSleep;
    boolean upgrading;
    String injectFailure;

    public Engine(String module, Progress progress) { this.module = module; this.progress = progress; }
    public static boolean region(String value) { return value.equals("cn") || value.equals("global"); }
    public static String q(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
    public static JSONObject read(File file) throws Exception { return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
    public static void write(File file, JSONObject value) throws Exception {
        File next = new File(file + ".new");
        try (FileOutputStream stream = new FileOutputStream(next)) {
            stream.write(value.toString(2).getBytes(StandardCharsets.UTF_8)); stream.getFD().sync();
        }
        Files.move(next.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    public static String digest(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256"); byte[] bytes = new byte[1024 * 1024];
        try (InputStream stream = new FileInputStream(file)) { int size; while ((size = stream.read(bytes)) != -1) hash.update(bytes, 0, size); }
        StringBuilder value = new StringBuilder(); for (byte b : hash.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 255)); return value.toString();
    }
    public String shell(String code) throws Exception { return shell(code, 30000, 0); }
    public String shell(String code, long timeout, int... accepted) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/data/adb/magisk/busybox", "sh", "-c", code).redirectErrorStream(true);
        builder.environment().remove("ASH_STANDALONE");
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) { byte[] buffer = new byte[8192]; int size;
                while ((size = stream.read(buffer)) != -1) output.write(buffer, 0, size);
            } catch (IOException ignored) { /* process timeout is reported by the waiting thread */ }
        });
        reader.start();
        if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) { process.destroyForcibly(); reader.join(2000); throw new IOException("Command timeout"); }
        reader.join(); String text = new String(output.toByteArray(), StandardCharsets.UTF_8).replace("\r", "").replace('\0', '\n');
        if (run != null) Files.write(new File(run, "commands.log").toPath(), ("$ " + code + "\n" + text + "\nEXIT=" + process.exitValue() + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        for (int status : accepted) if (process.exitValue() == status) return text;
        throw new IOException("Command failed (" + process.exitValue() + "): " + text.substring(0, Math.min(600, text.length())));
    }
    static String match(String pattern, String text) throws Exception {
        Matcher matcher = Pattern.compile(pattern, Pattern.MULTILINE).matcher(text);
        if (!matcher.find()) throw new IOException("Missing expected system field: " + pattern);
        return matcher.group(1);
    }
    public String current() throws Exception { String value = shell("cat " + STATE + "/current").trim(); if (!region(value)) throw new IOException("Unknown data profile"); return value; }
    JSONObject profile(String name) throws Exception {
        return loadProfile(name, false);
    }
    JSONObject loadProfile(String name, boolean legacy) throws Exception {
        File installedCn = new File(STATE, "installed-cn.json");
        if (!legacy && name.equals("cn") && installedCn.isFile()) return read(installedCn);
        JSONObject value = read(new File(module + "/profiles/" + (legacy ? "upgrade-source-" : "") + name + ".json"));
        for (String role : ROLES) {
            JSONObject apk = value.getJSONObject("apks").getJSONObject(role);
            if (!apk.getString("path").startsWith("/")) apk.put("path", new File(module, apk.getString("path")).getAbsolutePath());
        }
        value.put("install_script", module + "/install-" + (legacy ? "upgrade-source-" : "") + name + ".sh");
        return value;
    }
    public boolean needsUpgrade() { return !layoutFile.isFile(); }
    void begin(String destination, String failure, boolean upgrade) throws Exception {
        if (!region(destination)) throw new IllegalArgumentException("Unknown region");
        pending = null; inventory = null; guard = null; records = new JSONArray(); holdSleep = false;
        run = new File(STATE + "/headset/runs/" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));
        if (!run.mkdirs()) throw new IOException("Cannot create transaction directory");
        injectFailure = failure; upgrading = upgrade; sourceName = current(); targetName = destination;
        source = loadProfile(sourceName, upgrade); target = profile(targetName);
    }
    public void start(String destination, String failure) throws Exception {
        if (needsUpgrade()) throw new IOException("请先完成八组件升级，再切换区域。");
        begin(destination, failure, false);
        if (sourceName.equals(targetName)) { verifyApks(source, true); progress.update("已完成", "已经是所选区域，无需切换。"); return; }
        SwitchTransaction.run(this);
    }
    public void upgrade(String failure) throws Exception {
        if (!needsUpgrade()) { verifyApks(profile(current()), true); return; }
        begin(current(), failure, true);
        SwitchTransaction.run(this);
    }
    public void recover() throws Exception {
        loadPending();
        if (!digest(new File(module, "system/app/PicoRegionSwitch/PicoRegionSwitch.apk")).equals(pending.getString("executor_sha256"))) {
            String reply = shell("CLASSPATH=" + q(pending.getString("executor")) + " app_process /system/bin com.pico4.regionswitch.Backend --recover-pinned " + q(module), 300000, 0);
            if (!reply.contains("PINNED_RECOVERY_SUCCESS") || pendingFile.exists()) throw new IOException("Pinned recovery did not complete");
            return;
        }
        recoverPinned();
    }
    void recoverPinned() throws Exception {
        loadPending();
        File saved = new File(run, "transaction.json");
        if (saved.isFile() && read(saved).optString("phase").equals("verify-source-health")) {
            // Source code and roots were already restored; a failed health check must not
            // repeat two package installs and replace the very process record being checked.
            verifyApks(source, true); verifyRows(rows); healthy(source); step("current-source");
        } else SwitchTransaction.recover(this);
        step("clear-journal"); step("finish");
    }
    void prepareUninstall() throws Exception {
        if (!current().equals("cn") || pendingFile.exists()) throw new IOException("Return to complete CN before removal");
        verifyApks(profile("cn"), true);
        File saved = new File(STATE + "/headset/uninstall-cache-" + System.currentTimeMillis()); saved.mkdirs();
        JSONArray caches = new JSONArray();
        String listing = shell("find /data/system/package_cache -mindepth 2 -maxdepth 2 -type f -name pvrfit-16");
        for (String path : listing.split("\n")) {
            if (path.isEmpty()) continue;
            if (!path.matches("/data/system/package_cache/[a-zA-Z0-9_-]+/pvrfit-16")) throw new IOException("Unexpected cache path");
            File backup = new File(saved, "pvrfit-16-" + caches.length() + ".bin"); Files.copy(new File(path).toPath(), backup.toPath());
            if (!digest(backup).equals(digest(new File(path)))) throw new IOException("Cache backup differs");
            caches.put(new JSONObject().put("path", path).put("backup", backup.getAbsolutePath()).put("sha256", digest(backup)));
            write(new File(saved, "caches.json"), new JSONObject().put("caches", caches));
            Files.delete(new File(path).toPath());
        }
        write(new File(STATE, "uninstall-ready.json"), new JSONObject().put("profile", "cn").put("cache_backup", saved.getAbsolutePath()));
    }
    void loadPending() throws Exception {
        pending = read(pendingFile); run = new File(pending.getString("run")); inventory = pending.getJSONObject("inventory");
        if (pending.optInt("schema") != 2) throw new IOException("旧版事务须使用原build07执行器恢复，不能按新配置接管。");
        sourceName = pending.getString("source"); targetName = pending.getString("target");
        source = pending.getJSONObject("source_profile"); target = pending.getJSONObject("target_profile"); rows = pending.getJSONArray("rows");
        upgrading = pending.getBoolean("upgrading");
        if (!digest(new File(pending.getString("executor"))).equals(pending.getString("executor_sha256"))) throw new IOException("Saved recovery executor differs");
        verifyApks(source, false); verifyApks(target, false);
        holdSleep = inventory.getBoolean("sleeping");
    }
    JSONObject pinProfile(JSONObject original, String tag) throws Exception {
        JSONObject value = new JSONObject(original.toString()); File directory = new File(run, tag);
        if (!directory.mkdir()) throw new IOException("Cannot create version-bound APK directory");
        for (String role : ROLES) {
            JSONObject apk = value.getJSONObject("apks").getJSONObject(role);
            File pinned = new File(directory, role + ".apk");
            Files.createLink(pinned.toPath(), new File(apk.getString("path")).toPath());
            apk.put("path", pinned.getAbsolutePath());
        }
        String script = new String(Files.readAllBytes(new File(original.getString("install_script")).toPath()), StandardCharsets.UTF_8);
        for (String role : ROLES) script = script.replace(q(original.getJSONObject("apks").getJSONObject(role).getString("path")), q(new File(directory, role + ".apk").getAbsolutePath()));
        script = script.replace("/data/adb/modules/pico-region-downgrade/packages/" + original.getString("name") + "/", directory.getAbsolutePath() + "/");
        File install = new File(run, "install-" + tag + ".sh"); Files.write(install.toPath(), script.getBytes(StandardCharsets.UTF_8));
        value.put("install_script", install.getAbsolutePath()).put("install_sha256", digest(install));
        verifyApks(value, false); write(new File(run, tag + ".json"), value); return value;
    }
    String bank(JSONObject row, String name) throws Exception { return BANK[row.getInt("kind")] + "/" + name + "/" + row.getString("package"); }
    String metadata(String path) throws Exception {
        return shell("if [ -d " + q(path) + " ]; then stat -c '%u %g %a %i' " + q(path) + "; ls -Zd " + q(path) + "; else echo ABSENT; fi").trim();
    }
    JSONArray rowSnapshot(String name) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < ROLES.length; i++) for (int kind = 0; kind < KINDS.length; kind++) {
            String path = name == null ? LIVE[kind] + "/" + PACKAGES[i] : BANK[kind] + "/" + name + "/" + PACKAGES[i];
            String value = metadata(path);
            JSONObject row = new JSONObject().put("role", ROLES[i]).put("package", PACKAGES[i]).put("kind", kind)
                .put("path", LIVE[kind] + "/" + PACKAGES[i]).put("exists", !value.equals("ABSENT"));
            if (!value.equals("ABSENT")) {
                String[] lines = value.split("\n"), fields = lines[0].split(" ");
                row.put("uid", fields[0]).put("gid", fields[1]).put("mode", fields[2]).put("inode", fields[3]).put("label", lines[1].split(" ")[0]);
            }
            result.put(row);
        }
        return result;
    }
    void verifyRows(JSONArray expected) throws Exception {
        for (int i = 0; i < expected.length(); i++) {
            JSONObject row = expected.getJSONObject(i); if (!row.getBoolean("exists")) continue;
            String value = metadata(row.getString("path")); String[] lines = value.split("\n");
            String identity = row.getString("uid") + " " + row.getString("gid") + " " + row.getString("mode") + " " + row.getString("inode");
            if (!lines[0].equals(identity) || !lines[1].split(" ")[0].equals(row.getString("label"))) throw new IOException("Data root identity changed: " + row.getString("role"));
        }
    }
    static JSONArray services(String raw) throws Exception {
        LinkedHashMap<String, JSONObject> found = new LinkedHashMap<>();
        for (String block : raw.split("(?m)(?=^  \\* (?:Restarting )?ServiceRecord)")) {
            Matcher m = Pattern.compile("ServiceRecord\\{[^\\n]* u0 ([^\\s}]+)").matcher(block);
            if (m.find() && block.contains("startRequested=true")) found.put(m.group(1), new JSONObject().put("component", m.group(1)));
        }
        return new JSONArray(found.values());
    }
    JSONObject inspect() throws Exception {
        JSONObject result = new JSONObject().put("boot", shell("cat /proc/sys/kernel/random/boot_id").trim())
            .put("sleeping", shell("dumpsys power").contains("mWakefulness=Asleep"));
        JSONObject core = new JSONObject(), components = new JSONObject();
        for (String name : CORE) core.put(name, shell("pidof " + name + " || true").trim());
        for (int i = 0; i < ROLES.length; i++) {
            String raw = shell("dumpsys package " + PACKAGES[i]);
            components.put(ROLES[i], new JSONObject().put("uid", match("^\\s*userId=(\\d+)", raw))
                .put("enabled", Integer.parseInt(match("^\\s*User 0: .*?enabled=(\\d+)", raw)))
                .put("services", services(shell("dumpsys activity services " + PACKAGES[i]))));
        }
        return result.put("core", core).put("components", components);
    }
    void verifyApks(JSONObject profile, boolean installed) throws Exception {
        JSONObject apks = profile.getJSONObject("apks");
        for (int i = 0; i < ROLES.length; i++) {
            JSONObject apk = apks.getJSONObject(ROLES[i]);
            if (!apk.getString("package").equals(PACKAGES[i])) throw new IOException("Package identity mismatch");
            String path = installed ? shell("pm path " + PACKAGES[i]).trim().replaceFirst("^package:", "") : apk.getString("path");
            if (path.contains("\n") || !digest(new File(path)).equals(apk.getString("sha256"))) throw new IOException("APK differs from verified profile: " + ROLES[i]);
        }
    }
    String helper(String... arguments) throws Exception {
        String caller = pending == null ? APK : systemCaller(new File(pending.getString("executor"))).getAbsolutePath();
        StringBuilder code = new StringBuilder("CLASSPATH=" + q(caller) + " app_process /system/bin PicoRegionServiceCall");
        for (String argument : arguments) code.append(" ").append(q(argument));
        return shell("su 1000 -c " + q(code.toString()));
    }
    File systemCaller(File executor) throws Exception {
        // UID1000 must read this code without access to the root-only journal/data tree.
        File directory = new File("/data/system/pico-region-callers"); directory.mkdir();
        File caller = new File(directory, digest(executor) + ".apk");
        if (!caller.isFile()) Files.copy(executor.toPath(), caller.toPath());
        if (!digest(caller).equals(digest(executor))) throw new IOException("System caller bytes differ");
        shell("chown 0:1000 " + q(directory.toString()) + " " + q(caller.toString()) + "; chmod 750 " + q(directory.toString()) + "; chmod 440 " + q(caller.toString()) + "; chcon u:object_r:system_data_file:s0 " + q(directory.toString()) + " " + q(caller.toString()));
        return caller;
    }
    JSONObject ui() throws Exception { return new JSONObject().put("locales", match("^CONFIG_LOCALES=(.+)$", helper("--configuration"))).put("timezone", shell("getprop persist.sys.timezone").trim()); }
    String country() throws Exception { return shell("od -An -tx1 /mnt/vendor/persist/picoconfig/countryCode").trim(); }
    String accounts() throws Exception {
        TreeSet<String> list = new TreeSet<>(); Matcher m = Pattern.compile("(?m)^\\s*(Account \\{name=.*\\})$").matcher(shell("dumpsys account"));
        while (m.find()) list.add(m.group(1)); return list.toString();
    }
    void setRegion(JSONObject profile) throws Exception {
        String value = profile.getString("region_value"), reply = helper(value, "false");
        if (!reply.contains("CALL_RETURNED country=" + value)) throw new IOException("Region service did not acknowledge");
        JSONObject saved = pending.getJSONObject("ui");
        if (!helper("--restore-ui", saved.getString("locales"), saved.getString("timezone")).contains("UI_CONFIG_RESTORED")) throw new IOException("UI restore failed");
        if (!shell("settings get global user_settings_initialized").trim().equals(value) || !country().equals(pending.getString("country"))) throw new IOException("Regional settings mismatch");
    }
    void stopService(String component) throws Exception {
        String reply = shell("am stopservice --user 0 -n " + q(component), 30000, 0, 1, 255);
        if (!Pattern.compile("(?m)^Service stopped\\.?$|^Service not stopped: was not running\\.$").matcher(reply).find()) throw new IOException("Service stop failed");
    }
    String enabledCommand(int enabled) { return new String[]{"default-state", "enable", "disable", "disable-user", "disable-until-used"}[enabled]; }
    void enabled(int i, String command) throws Exception {
        String uid = inventory.getJSONObject("components").getJSONObject(ROLES[i]).getString("uid");
        shell("su " + uid + " -c " + q("pm " + command + " --user 0 " + PACKAGES[i]));
    }
    String lock() { return "set -eu\nexec 9>" + STATE + "/guard.lock\n/data/adb/magisk/busybox flock -x 9\n"; }
    String signals(JSONArray list, String signal) throws Exception {
        StringBuilder code = new StringBuilder(":\n");
        for (int i = 0; i < list.length(); i++) {
            JSONObject r = list.getJSONObject(i); String pid = r.getString("pid");
            code.append("if [ -r /proc/").append(pid).append("/stat ] && [ \"$(awk '{print $22}' /proc/").append(pid)
                .append("/stat)\" = ").append(q(r.getString("ticks"))).append(" ] && [ \"$(tr '\\000' '\\n' < /proc/")
                .append(pid).append("/cmdline | head -n 1)\" = ").append(q(r.getString("name"))).append(" ]; then kill -")
                .append(signal).append(" ").append(pid).append("; fi\n");
        }
        return code.toString();
    }
    String cancel(String prefix) {
        return "if [ -f " + prefix + ".pid ]; then read pid ticks < " + prefix + ".pid; if [ -r /proc/$pid/stat ] && [ \"$(awk '{print $22}' /proc/$pid/stat)\" = \"$ticks\" ]; then kill -TERM \"$pid\" 2>/dev/null || true; fi; fi\n";
    }
    void loadGuard() throws Exception {
        File active = new File(STATE, "guard-current");
        if (!active.isFile()) { guard = null; records = new JSONArray(); return; }
        guard = new String(Files.readAllBytes(active.toPath()), StandardCharsets.UTF_8).trim();
        if (!guard.matches(Pattern.quote(STATE) + "/guard-[A-Za-z0-9_.-]+")) throw new IOException("Unexpected guard path");
        records = read(new File(guard + ".json")).getJSONArray("records");
    }
    void freeze() throws Exception {
        loadGuard(); String old = guard; JSONArray held = new JSONArray();
        for (String line : shell("/system/bin/ps -A -o PID,NAME").split("\n")) {
            String[] parts = line.trim().split("\\s+"); if (parts.length != 2) continue;
            if (regionalProcess(parts[1]))
                held.put(new JSONObject().put("pid", parts[0]).put("name", parts[1]).put("ticks", shell("awk '{print $22}' /proc/" + parts[0] + "/stat").trim()));
        }
        String prefix = STATE + "/guard-headset-" + UUID.randomUUID().toString();
        String guardCode = "#!/system/bin/sh\nset -u\nsleep 45 &\nsleeper=$!\ntrap 'kill \"$sleeper\" 2>/dev/null || true; exit 0' TERM INT\nwait \"$sleeper\"\n" + lock()
            + "[ \"$(cat " + STATE + "/guard-current 2>/dev/null)\" = " + q(prefix) + " ] || exit 0\necho EXPIRED > " + prefix + ".expired\n"
            + "if [ \"$(cat " + prefix + ".policy)\" = resume ]; then\n" + signals(held, "CONT") + "else\n" + signals(held, "KILL") + "fi\n";
        Files.write(new File(prefix + ".sh").toPath(), guardCode.getBytes(StandardCharsets.UTF_8));
        write(new File(prefix + ".json"), new JSONObject().put("records", held));
        String publish = lock() + "policy=resume\n" + (old == null ? "" : "policy=$(cat " + old + ".policy)\n")
            + "chmod 700 " + prefix + ".sh\necho \"$policy\" > " + prefix + ".policy\nread uptime rest < /proc/uptime\necho $(( ${uptime%%.*} + 45 )) > " + prefix + ".deadline\n"
            + ASH + " " + prefix + ".sh 9>&- </dev/null >/dev/null 2>&1 & pid=$!\nticks=$(awk '{print $22}' /proc/$pid/stat)\necho \"$pid $ticks\" > " + prefix + ".pid\n"
            + "echo " + prefix + " > " + STATE + "/guard-current.new\nmv " + STATE + "/guard-current.new " + STATE + "/guard-current\n"
            + signals(held, "STOP") + (old == null ? "" : cancel(old));
        shell(publish); guard = prefix; records = held; quiescent();
    }
    String quiescence() throws Exception {
        StringBuilder code = new StringBuilder("/system/bin/ps -A -o PID,NAME | while read pid name; do\ncase \"$name\" in\n");
        for (int i = 0; i < PACKAGES.length; i++) { if (i != 0) code.append("|"); code.append(PACKAGES[i]).append("|").append(PACKAGES[i]).append(":*"); }
        for (String name : PROCESS_ALIASES) code.append("|").append(name);
        code.append(")\ncase \"$pid:$name\" in\n");
        for (int i = 0; i < records.length(); i++) { JSONObject r = records.getJSONObject(i); code.append(r.getString("pid")).append(":").append(r.getString("name")).append(") expected=").append(q(r.getString("ticks"))).append(" ;;\n"); }
        return code + "*) echo UNCONTROLLED; exit 1 ;;\nesac\n[ \"$(awk '{print $22}' /proc/$pid/stat)\" = \"$expected\" ]\nfor f in /proc/$pid/task/*/stat; do\nstate=$(awk '{sub(/^[0-9]+ \\(.*\\) /, \"\"); print $1}' \"$f\")\n[ \"$state\" = T ] || [ \"$state\" = t ] || { echo THREAD_NOT_STOPPED; exit 1; }\ndone\n;;\nesac\ndone\n";
    }
    String lease() {
        return "[ \"$(cat " + STATE + "/guard-current)\" = " + q(guard) + " ]\n[ ! -f " + guard + ".expired ]\nread uptime rest < /proc/uptime\n[ \"${uptime%%.*}\" -lt \"$(cat " + guard + ".deadline)\" ]\n";
    }
    void quiescent() throws Exception { if (guard == null) throw new IOException("No freeze guard"); shell(lock() + lease() + quiescence()); }
    void bankScript(String code) throws Exception { shell(lock() + lease() + quiescence() + "echo kill > " + guard + ".policy\n" + code); }
    void release() throws Exception {
        loadGuard(); if (guard == null) return;
        shell(lock() + "if [ \"$(cat " + guard + ".policy)\" = resume ]; then\n" + signals(records, "CONT") + "else\n" + signals(records, "KILL") + "fi\n" + cancel(guard) + "rm -f " + STATE + "/guard-current\n");
        guard = null; records = new JSONArray();
    }
    void stop() throws Exception {
        swiftBinding(false);
        for (int i = 0; i < ROLES.length; i++) {
            JSONArray services = services(shell("dumpsys activity services " + PACKAGES[i]));
            for (int j = 0; j < services.length(); j++) stopService(services.getJSONObject(j).getString("component"));
        }
        for (int i = 0; i < ROLES.length; i++) { enabled(i, "disable-user"); shell("am force-stop --user 0 " + PACKAGES[i]); }
        freeze();
    }
    static boolean regionalProcess(String name) {
        for (String alias : PROCESS_ALIASES) if (name.equals(alias)) return true;
        for (String pkg : PACKAGES) if (name.equals(pkg) || name.startsWith(pkg + ":")) return true;
        return false;
    }
    static String serviceStart(String component) {
        return "am startservice --user 0 -n " + q(component);
    }
    void swiftBinding(boolean connect) throws Exception {
        if (!helper("--swift-bind", Boolean.toString(connect)).contains("SWIFT_BIND_REQUESTED=" + connect)) throw new IOException("Swift binding request failed");
        if (connect) {
            long until = SystemClock.elapsedRealtime() + 10000;
            while (SystemClock.elapsedRealtime() < until) {
                if (swiftBound(shell("dumpsys activity services com.pvr.swift"))) return;
                SystemClock.sleep(250);
            }
            throw new IOException("Swift system binding did not become active");
        }
    }
    static boolean swiftBound(String raw) {
        return Pattern.compile("app=ProcessRecord\\{[^\\n]*com\\.pvr\\.swift\\.service").matcher(raw).find()
            && raw.contains("received=true hasBound=true") && raw.contains(":system/");
    }
    void resume(JSONObject profile) throws Exception {
        for (int i = 0; i < ROLES.length; i++) {
            JSONArray declared = profile.getJSONObject("apks").getJSONObject(ROLES[i]).getJSONArray("services");
            Set<String> names = new HashSet<>(); for (int j = 0; j < declared.length(); j++) names.add(declared.getJSONObject(j).getString("name"));
            JSONArray running = services(shell("dumpsys activity services " + PACKAGES[i]));
            for (int j = 0; j < running.length(); j++) {
                String component = running.getJSONObject(j).getString("component"), name = component.split("/", 2)[1];
                if (name.startsWith(".")) name = PACKAGES[i] + name;
                if (!names.contains(name)) stopService(component);
            }
        }
        release();
        for (int i = 0; i < ROLES.length; i++) {
            JSONObject saved = inventory.getJSONObject("components").getJSONObject(ROLES[i]);
            int active = Integer.parseInt(match("^\\s*User 0: .*?enabled=(\\d+)", shell("dumpsys package " + PACKAGES[i])));
            if (active != saved.getInt("enabled")) enabled(i, enabledCommand(saved.getInt("enabled")));
            if (i == 7 && saved.getInt("enabled") <= 1) swiftBinding(true);
            JSONArray declared = profile.getJSONObject("apks").getJSONObject(ROLES[i]).getJSONArray("services"), prior = saved.getJSONArray("services");
            for (int j = 0; j < prior.length(); j++) {
                String component = prior.getJSONObject(j).getString("component"), name = component.split("/", 2)[1], use = null;
                if (name.startsWith(".")) name = PACKAGES[i] + name;
                for (int k = 0; k < declared.length(); k++) {
                    JSONObject entry = declared.getJSONObject(k);
                    if (entry.getString("name").equals(name) || (i == 3 && entry.getJSONArray("actions").toString().contains("com.picovr.pvrfit.sys.server"))) use = entry.getString("name");
                }
                if (use != null) {
                    String reply = shell(serviceStart(PACKAGES[i] + "/" + use));
                    if (reply.contains("Error:") || reply.contains("Exception")) throw new IOException("Service restart failed");
                }
            }
        }
    }
    void createLive(StringBuilder code, JSONObject row) throws Exception {
        if (!row.getBoolean("exists")) return;
        String live = q(row.getString("path"));
        code.append("if [ ! -e ").append(live).append(" ]; then mkdir -m ").append(row.getString("mode")).append(" ").append(live)
            .append("; chown ").append(row.getString("uid")).append(":").append(row.getString("gid")).append(" ").append(live)
            .append("; chcon ").append(q(row.getString("label"))).append(" ").append(live).append("; fi\n");
    }
    void activate() throws Exception {
        StringBuilder code = new StringBuilder("umask 077\n");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i); if (!row.getBoolean("move")) continue;
            String old = bank(row, row.getString("source_bank")), next = bank(row, row.getString("target_bank")), live = row.getString("path");
            code.append("[ ! -e ").append(q(old)).append(" ] && [ ! -e ").append(q(old + ".absent")).append(" ]\nmkdir -p ").append(q(new File(old).getParent())).append(" ").append(q(new File(next).getParent())).append("\n");
            code.append("if [ -e ").append(q(live)).append(" ]; then ").append(RENAME).append(" ").append(q(live)).append(" ").append(q(old)).append("; else touch ").append(q(old + ".absent")).append("; fi\n");
            code.append("if [ -d ").append(q(next)).append(" ]; then ").append(RENAME).append(" ").append(q(next)).append(" ").append(q(live)).append("; fi\nrm -f ").append(q(next + ".absent")).append("\n"); createLive(code, row);
        }
        bankScript(code.toString());
    }
    void park() throws Exception {
        StringBuilder code = new StringBuilder("umask 077\n");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i); if (!row.getBoolean("move")) continue;
            String old = bank(row, row.getString("source_bank")), saved = bank(row, row.getString("target_bank")), live = row.getString("path");
            code.append("if [ -d ").append(q(old)).append(" ] || [ -f ").append(q(old + ".absent")).append(" ]; then\nmkdir -p ").append(q(new File(saved).getParent())).append("\n")
                .append("if [ ! -e ").append(q(saved)).append(" ] && [ -e ").append(q(live)).append(" ]; then ").append(RENAME).append(" ").append(q(live)).append(" ").append(q(saved)).append("; fi\n");
            createLive(code, row); code.append("fi\n");
        }
        bankScript(code.toString());
    }
    void restore() throws Exception {
        StringBuilder code = new StringBuilder("umask 077\n");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i); if (!row.getBoolean("move")) continue;
            String old = bank(row, row.getString("source_bank")), live = row.getString("path"), scratch = bank(row, "scratch-" + run.getName());
            code.append("if [ -d ").append(q(old)).append(" ] || [ -f ").append(q(old + ".absent")).append(" ]; then\nmkdir -p ").append(q(new File(scratch).getParent())).append("\n")
                .append("if [ -e ").append(q(live)).append(" ]; then inode=$(stat -c %i ").append(q(live)).append("); ").append(RENAME).append(" ").append(q(live)).append(" ").append(q(scratch)).append("-\"$inode\"; fi\n")
                .append("if [ -d ").append(q(old)).append(" ]; then ").append(RENAME).append(" ").append(q(old)).append(" ").append(q(live)).append("; fi\nrm -f ").append(q(old + ".absent")).append("\nfi\n");
        }
        bankScript(code.toString());
    }
    void install(JSONObject profile) throws Exception {
        verifyApks(profile, false);
        File script = new File(profile.getString("install_script"));
        if (!digest(script).equals(profile.getString("install_sha256"))) throw new IOException("Saved install plan differs");
        freeze(); // Renew the bounded lease immediately before the measured 30s+ eight-APK commit.
        long until = SystemClock.elapsedRealtime() + 90000;
        shell("setprop " + GATE + " " + until);
        try {
            String reply = shell(ASH + " " + q(script.getAbsolutePath()), 240000, 0);
            if (!reply.contains("REGION_ATOMIC_INSTALL_SUCCESS")) throw new IOException("Atomic parent commit missing");
            verifyApks(profile, true);
        } finally { shell("setprop " + GATE + " 0"); }
    }
    void preflight() throws Exception {
        if (new File(STATE, "pending.json").exists() || pendingFile.exists() || new File(STATE, "guard-current").exists()) throw new IOException("An interrupted transaction needs recovery first");
        if (!shell("pidof VirtualDesktop.Android || true").trim().isEmpty()) throw new IOException("请先退出 Virtual Desktop");
        JSONObject framework = read(new File(STATE, "framework.json")), base = read(new File(STATE, "fitness-base.json")), cache = read(new File(STATE, "fitness-cache.json"));
        for (String flag : new String[]{"disable", "remove", "skip_mount"}) if (new File(module, flag).exists()) throw new IOException("模块已停用或待移除");
        if (!digest(new File("/system/framework/services.jar")).equals(framework.getString("sha256")) ||
            !digest(new File("/system/priv-app/pvrfit/pvrfit.apk")).equals(base.getJSONObject("apk").getString("sha256")) ||
            !cache.getString("base_sha256").equals(base.getJSONObject("apk").getString("sha256"))) throw new IOException("请先加载已验证的框架和底包支持");
        if (!shell("settings get global user_settings_initialized").trim().equals(source.getString("region_value"))) throw new IOException("当前区域与数据档位不一致");
        String forced = shell("getprop persist.countrycode.forcible").trim();
        if (!forced.isEmpty() && !forced.equals(target.getString("region_value"))) throw new IOException("系统强制国家码冲突");
        verifyApks(source, true); verifyApks(source, false); verifyApks(target, false);
        for (String role : ROLES) if (!source.getJSONObject("apks").getJSONObject(role).getJSONArray("signer_sha256").toString().equals(target.getJSONObject("apks").getJSONObject(role).getJSONArray("signer_sha256").toString())) throw new IOException("Profile signer differs: " + role);
        inventory = inspect(); holdSleep = inventory.getBoolean("sleeping"); rows = rowSnapshot(null); targetRows = rowSnapshot(targetName);
        configureRows();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i); if (!row.getBoolean("move")) continue;
            String old = bank(row, row.getString("source_bank"));
            if (new File(old).exists() || new File(old + ".absent").exists()) throw new IOException("Source data bank is occupied: " + row.getString("role"));
        }
        source = pinProfile(source, "source"); target = pinProfile(target, "target");
        // Own panel hotfixes preserve Magisk's bound inode, so the small executor must be copied, not linked.
        File executor = new File(run, "executor.apk"); Files.copy(new File(module, "system/app/PicoRegionSwitch/PicoRegionSwitch.apk").toPath(), executor.toPath());
        pending = new JSONObject().put("schema", 2).put("kind", "headset-switch").put("source", sourceName).put("target", targetName).put("run", run.getAbsolutePath())
            .put("source_profile", source).put("target_profile", target).put("executor", executor.getAbsolutePath()).put("executor_sha256", digest(executor))
            .put("upgrading", upgrading).put("source_layout", layoutFile.isFile() ? read(layoutFile) : JSONObject.NULL)
            .put("inventory", inventory).put("rows", rows).put("ui", ui()).put("country", country()).put("accounts", accounts());
    }
    void configureRows() throws Exception {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            boolean move = !upgrading || (sourceName.equals("global") && row.getString("role").equals("swift"));
            row.put("move", move).put("source_bank", upgrading ? "cn" : sourceName).put("target_bank", targetName);
            if (!move) targetRows.put(i, new JSONObject(row.toString()));
        }
    }
    void snapshot() throws Exception {
        quiescent(); StringBuilder paths = new StringBuilder();
        for (int i = 0; i < rows.length(); i++) if (rows.getJSONObject(i).getBoolean("exists")) paths.append(" ").append(q(rows.getJSONObject(i).getString("path").substring(1)));
        String archive = run + "/app-data-before.tar", sockets = run + "/sockets.txt";
        shell("set -e; cd /; find" + paths + " -type s > " + q(sockets) + "; tar -cf " + q(archive) + " -X " + q(sockets) + paths, 35000, 0);
        quiescent();
    }
    void verifyBackup() throws Exception {
        Set<String> expected = new HashSet<>(); for (int i = 0; i < rows.length(); i++) if (rows.getJSONObject(i).getBoolean("exists")) expected.add(rows.getJSONObject(i).getString("path").substring(1));
        File archive = new File(run, "app-data-before.tar"); int entries = TarVerifier.verify(archive, expected);
        write(new File(run, "backup.json"), new JSONObject().put("entries", entries).put("size", archive.length()).put("sha256", digest(archive)).put("frozen_snapshot", true));
    }
    void healthy(JSONObject profile) throws Exception {
        verifyApks(profile, true);
        if (!swiftBound(shell("dumpsys activity services com.pvr.swift"))) throw new IOException("Swift system binding is not active");
        for (int i = 0; i < ROLES.length; i++) {
            int active = Integer.parseInt(match("^\\s*User 0: .*?enabled=(\\d+)", shell("dumpsys package " + PACKAGES[i])));
            if (active != inventory.getJSONObject("components").getJSONObject(ROLES[i]).getInt("enabled")) throw new IOException("Enabled state mismatch");
        }
        JSONObject actualUi = ui(), expectedUi = pending.getJSONObject("ui");
        if (!actualUi.getString("locales").equals(expectedUi.getString("locales")) || !actualUi.getString("timezone").equals(expectedUi.getString("timezone")) ||
            !accounts().equals(pending.getString("accounts")) || !country().equals(pending.getString("country"))) throw new IOException("Account/UI/factory-country changed");
        Map<String, String> before = new LinkedHashMap<>(); for (String name : new String[]{CORE[0], CORE[1], CORE[2], PACKAGES[0], PACKAGES[3], PROCESS_ALIASES[0]}) before.put(name, shell("pidof " + name + " || true").trim());
        SystemClock.sleep(1500);
        if (!swiftBound(shell("dumpsys activity services com.pvr.swift"))) throw new IOException("Swift system binding was lost");
        for (Map.Entry<String, String> entry : before.entrySet()) if (entry.getValue().isEmpty() || !entry.getValue().equals(shell("pidof " + entry.getKey() + " || true").trim())) throw new IOException("Persistent process missing or restarting");
        if (shell("cat /proc/sys/kernel/random/boot_id").trim().equals(inventory.getString("boot")))
            for (String name : CORE) if (!before.get(name).equals(inventory.getJSONObject("core").getString(name))) throw new IOException("Core process restarted");
    }
    void current(String name) throws Exception {
        File next = new File(STATE, "current.new"); Files.write(next.toPath(), (name + "\n").getBytes(StandardCharsets.UTF_8));
        Files.move(next.toPath(), new File(STATE, "current").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    public void step(String name) throws Exception {
        progress.update(label(name), "任务在头显本地后台运行，无需连接电脑。");
        if (pending != null && !name.equals("preflight")) { pending.put("phase", name); write(new File(run, "transaction.json"), pending); }
        if (name.equals(injectFailure)) { injectFailure = null; throw new IOException("Injected failure at " + name); }
        switch (name) {
            case "preflight": preflight(); break;
            case "journal": write(pendingFile, pending); break;
            case "stop-source": case "stop-target": case "stop-source-again": stop(); break;
            case "snapshot": snapshot(); break;
            case "activate-data": activate(); break;
            case "set-target-region": setRegion(target); break;
            case "install-target": install(target); break;
            case "verify-target-data": verifyRows(targetRows); break;
            case "resume-target": resume(target); break;
            case "verify-backup": verifyBackup(); break;
            case "verify-target-health": healthy(target); break;
            case "current-target": current(targetName); write(layoutFile, new JSONObject().put("components", ROLES.length).put("current", targetName).put("profile", target)); break;
            case "park-target": park(); break;
            case "rollback-start": Files.deleteIfExists(new File(run, "completed.json").toPath()); break;
            case "set-source-region": setRegion(source); break;
            case "install-source-scratch": case "install-source-restored": install(source); break;
            case "restore-source-data": restore(); break;
            case "verify-source-data": verifyRows(rows); break;
            case "resume-source": resume(source); break;
            case "verify-source-health": healthy(source); break;
            case "current-source": current(sourceName); if (pending.isNull("source_layout")) Files.deleteIfExists(layoutFile.toPath()); else write(layoutFile, pending.getJSONObject("source_layout")); break;
            case "clear-journal": Files.deleteIfExists(pendingFile.toPath()); break;
            case "complete": write(new File(run, "completed.json"), new JSONObject().put("target", targetName).put("complete", true)); break;
            case "finish": if (holdSleep) shell("input keyevent KEYCODE_SLEEP"); break;
            default: throw new IllegalArgumentException(name);
        }
    }
    static String label(String phase) {
        switch (phase) {
            case "preflight": return "检查应用和底层支持";
            case "journal": return "记录恢复信息";
            case "stop-source": case "stop-target": case "stop-source-again": return "暂停区域应用";
            case "snapshot": return "生成原区域数据备份";
            case "activate-data": return "切换独立数据银行";
            case "set-target-region": return "更新区域设置";
            case "install-target": return "原子安装八组件";
            case "verify-target-data": return "核对目标区域数据";
            case "resume-target": return "恢复区域应用";
            case "verify-backup": return "完整校验数据备份";
            case "verify-target-health": return "检查应用运行状态";
            case "current-target": case "complete": case "clear-journal": case "finish": return "完成最终核对";
            case "rollback-start": return "准备恢复原区域";
            case "park-target": return "保全目标区域数据";
            case "set-source-region": return "恢复原区域设置";
            case "install-source-scratch": return "隔离数据并恢复原应用";
            case "restore-source-data": return "恢复原区域数据银行";
            case "install-source-restored": return "完成原应用恢复";
            case "verify-source-data": return "核对原区域数据";
            case "resume-source": return "恢复原区域应用运行";
            case "verify-source-health": return "检查恢复后的运行状态";
            case "current-source": return "确认原区域恢复完成";
            default: return "正在恢复原区域：" + phase;
        }
    }
}
