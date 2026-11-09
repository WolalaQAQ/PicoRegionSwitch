package com.pico4.regionswitch;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import org.json.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Module-owned worker; the panel only writes fixed requests inside its Android sandbox. */
public final class Backend {
    static final File HOME = new File(Engine.STATE, "headset");
    static final File CHANNEL = new File("/data/user_de/0/" + Engine.APP + "/files/control");
    static String phase = "就绪", message = "选择国区或全球区，无需连接电脑。";
    static boolean busy;
    static String requestId = "";
    static boolean success;
    static Engine engine;
    static int appUid;
    static File lockFile = new File(HOME, "daemon.lock");

    static boolean channelReady() throws Exception {
        return CHANNEL.isDirectory() && CHANNEL.getCanonicalPath().equals(CHANNEL.getAbsolutePath()) &&
            Os.lstat(CHANNEL.getAbsolutePath()).st_uid == appUid;
    }
    static JSONObject state() throws Exception {
        boolean installing = new FirstInstall(engine).pending();
        String current = new File(Engine.STATE, "current").isFile() ? engine.current() : "";
        return new JSONObject().put("current", current).put("busy", busy).put("request_id", requestId).put("success", success)
            .put("initialization_pending", installing)
            .put("upgrade_needed", engine.needsUpgrade()).put("components", engine.needsUpgrade() ? 7 : Engine.ROLES.length)
            .put("pending", new File(Engine.STATE, "headset-pending.json").isFile() || new File(Engine.STATE, "pending.json").isFile())
            .put("ready", !installing && new File(engine.module, "system/framework/services.jar").isFile() && !new File(engine.module, "disable").exists() && !new File(engine.module, "remove").exists())
            .put("phase", phase).put("message", message).put("updated", System.currentTimeMillis());
    }
    static void publish() throws Exception {
        if (!channelReady()) return;
        File next = new File(CHANNEL, "state.new");
        Files.write(next.toPath(), state().toString().getBytes(StandardCharsets.UTF_8));
        String label = engine.shell("ls -Zd " + Engine.q(CHANNEL.getAbsolutePath())).split("\\s+")[0];
        engine.shell("set -e; chown " + appUid + ":" + appUid + " " + Engine.q(next.getAbsolutePath()) +
            "; chmod 600 " + Engine.q(next.getAbsolutePath()) + "; chcon " + Engine.q(label) + " " + Engine.q(next.getAbsolutePath()));
        Files.move(next.toPath(), new File(CHANNEL, "state.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    static void progress(String next, String text) {
        phase = next; message = text;
        try { publish(); } catch (Exception error) { System.err.println("Panel state: " + error); }
    }
    static void execute(JSONObject request) throws Exception {
        String action = request.getString("action"), target = request.optString("target");
        if (!(action.equals("switch") && Engine.region(target)) && !action.equals("upgrade") && !action.equals("recover") && !action.equals("recover-install") && !action.equals("status") && !action.equals("prepare-uninstall")) throw new IOException("Unknown request");
        if (action.equals("status")) { publish(); return; }
        if (new FirstInstall(engine).pending() && !action.equals("recover-install")) throw new IOException("请先完成首次安装验证。");
        if (new File(Engine.STATE, "pending.json").exists()) throw new IOException("PC事务尚未恢复，请先使用PC维护入口。");
        requestId = request.optString("id"); success = false;
        busy = true; Files.write(new File(Engine.STATE, "headset-busy").toPath(), request.toString().getBytes(StandardCharsets.UTF_8));
        try {
            if (action.equals("recover-install")) {
                new FirstInstall(engine).finishBoot(); phase = "首次安装完成"; message = "国区原应用和数据已保留。现在可以切换国区／全球区。"; success = true; return;
            }
            if (action.equals("recover")) engine.recover();
            else {
                String fault = request.optString("test_failure", "");
                if (!fault.isEmpty() && !new File(HOME, "allow-fault-tests").exists()) throw new IOException("Fault-test gate is closed");
                if (action.equals("upgrade")) engine.upgrade(fault);
                else engine.start(action.equals("prepare-uninstall") ? "cn" : target, fault);
            }
            phase = action.equals("upgrade") ? "八组件升级完成" : "切换完成";
            message = "当前为" + (engine.current().equals("cn") ? "国区" : "全球区") + "八组件。两区数据已保留，头显没有重启；底层区域属性保持原值。";
            if (engine.needsUpgrade()) { phase = "已恢复升级前状态"; message = "七组件及原国区体感已恢复，数据保留。完成八组件升级后才能使用新切区流程。"; }
            if (action.equals("prepare-uninstall")) {
                engine.prepareUninstall(); phase = "已准备好卸载"; message = "已恢复完整国区并保全／失效运动中心解析缓存。现在可在Magisk移除此模块并重启；两区数据银行和备份会保留。";
            }
            success = true;
        } catch (Exception error) {
            phase = action.equals("recover-install") ? "首次安装待验证" : new File(Engine.STATE, "headset-pending.json").exists() ? "恢复未完成" : "操作未完成，原区域已保留";
            message = error.getMessage();
            if (engine.run != null) Engine.write(new File(engine.run, "result.json"), new JSONObject().put("completed", false).put("error", error.toString()).put("pending", new File(Engine.STATE, "headset-pending.json").exists()));
            throw error;
        } finally {
            busy = false; Files.deleteIfExists(new File(Engine.STATE, "headset-busy").toPath()); publish();
        }
        if (engine.run != null) Engine.write(new File(engine.run, "result.json"), new JSONObject().put("completed", true).put("current", engine.current()));
    }
    static JSONObject request() throws Exception {
        if (!channelReady()) return null;
        File file = new File(CHANNEL, "request.json"); if (!file.isFile()) return null;
        if (Os.lstat(file.getAbsolutePath()).st_uid != appUid || !OsConstants.S_ISREG(Os.lstat(file.getAbsolutePath()).st_mode) || file.length() > 4096) throw new IOException("Invalid request file");
        JSONObject value = Engine.read(file);
        if (!value.getString("id").matches("[a-f0-9-]{36}")) throw new IOException("Invalid request identifier");
        Files.delete(file.toPath()); return value;
    }
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0) throw new SecurityException("Module worker requires root");
        if (args.length != 2 || !(args[0].equals("--serve") || args[0].equals("--status") || args[0].equals("--self-test") || args[0].equals("--recover-pinned")))
            throw new IllegalArgumentException("Usage: --serve|--status|--self-test|--recover-pinned MODULE");
        HOME.mkdirs(); engine = new Engine(args[1], Backend::progress);
        if (args[0].equals("--recover-pinned")) {
            JSONObject saved = Engine.read(engine.pendingFile);
            if (!Engine.digest(new File(System.getenv("CLASSPATH"))).equals(saved.getString("executor_sha256"))) throw new IOException("Wrong recovery executor");
            engine.recoverPinned(); System.out.println("PINNED_RECOVERY_SUCCESS"); return;
        }
        if (args[0].equals("--self-test")) { SelfTest.run(engine); return; }
        appUid = Integer.parseInt(Engine.match("^\\s*userId=(\\d+)", engine.shell("dumpsys package " + Engine.APP)));
        if (args[0].equals("--status")) { System.out.println(state().toString(2)); return; }
        try (RandomAccessFile file = new RandomAccessFile(lockFile, "rw"); FileLock lock = file.getChannel().tryLock()) {
            if (lock == null) throw new IOException("Module worker already running");
            if (new FirstInstall(engine).pending()) {
                try { execute(new JSONObject().put("action", "recover-install")); }
                catch (Exception error) { phase = "首次安装待验证"; message = error.getMessage(); System.err.println(error); }
            }
            // An interrupted local transaction retains its original source/data and can recover
            // before any further user request; PC journals remain the PC maintenance tool's job.
            if (new File(Engine.STATE, "headset-pending.json").isFile()) {
                try { execute(new JSONObject().put("action", "recover")); } catch (Exception error) { System.err.println(error); }
            } else Files.deleteIfExists(new File(Engine.STATE, "headset-busy").toPath());
            while (true) {
                try {
                    JSONObject command = request();
                    if (command != null) execute(command);
                    else if (channelReady() && !new File(CHANNEL, "state.json").isFile()) publish();
                }
                catch (Exception error) { System.err.println(error); phase = "请检查操作状态"; message = error.getMessage(); }
                SystemClock.sleep(1000);
            }
        }
    }
}
