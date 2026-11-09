package com.pico4.regionswitch;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** ART-side read-only parsing checks; the only writes are a disposable worker fixture. */
public final class SelfTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    public static void run(Engine engine) throws Exception {
        String raw = "  * ServiceRecord{abc u0 com.pvr.pvrfit/androidx.work.SystemJobService}\n    Bindings:\n  Restarting services:\n  * Restarting ServiceRecord{xyz u0 com.pvr.pvrfit/.core.FitnessService}\n    startRequested=true\n";
        JSONArray services = Engine.services(raw); check(services.length() == 1); check(services.getJSONObject(0).getString("component").endsWith("FitnessService"));
        check(!Engine.region("original")); check(Engine.region("cn")); check(Engine.region("global"));
        check(Engine.label("verify-target-data").equals("核对目标区域数据"));
        check(Engine.match("^\\s*User 0: .*?enabled=(\\d+)", "  User 0: installed=true enabled=0\n").equals("0"));
        check(engine.current().equals("global") || engine.current().equals("cn"));
        check(Engine.regionalProcess("com.pvr.swift.service")); check(!Engine.regionalProcess("com.pvr.swift.service.other"));
        check(Engine.serviceStart("com.pvr.swift/.service.SwiftService").contains("am startservice"));
        check(engine.shell("/system/bin/ps -A -o PID,NAME").contains("PID"));
        File caller = engine.systemCaller(new File(engine.module, "system/app/PicoRegionSwitch/PicoRegionSwitch.apk"));
        check(engine.shell("su 1000 -c " + Engine.q("CLASSPATH=" + Engine.q(caller.toString()) + " app_process /system/bin PicoRegionServiceCall --status")).contains("SERVICE_READY"));
        engine.verifyApks(engine.loadProfile(engine.current(), engine.needsUpgrade()), true);
        engine.verifyApks(engine.profile("cn"), false); engine.verifyApks(engine.profile("global"), false); checks += Engine.ROLES.length * 3;
        JSONObject inventory = engine.inspect(); check(inventory.getJSONObject("components").length() == Engine.ROLES.length);
        JSONArray rows = engine.rowSnapshot(null); check(rows.length() == Engine.ROLES.length * Engine.KINDS.length); engine.verifyRows(rows); checks += rows.length();
        File fixture = new File(Engine.STATE + "/headset/self-test-" + UUID.randomUUID()); fixture.mkdirs();
        try {
            String stat = "14247 (Jit thread pool) T 801 801\n";
            File input = new File(fixture, "stat"); Files.write(input.toPath(), stat.getBytes(StandardCharsets.UTF_8));
            check(engine.shell("awk '{sub(/^[0-9]+ \\(.*\\) /, \"\"); print $1}' " + Engine.q(input.getAbsolutePath())).trim().equals("T"));
            File content = new File(fixture, "marker"); Files.write(content.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
            File archive = new File(fixture, "fixture.tar"); engine.shell("cd " + Engine.q(fixture.toString()) + "; tar -cf fixture.tar marker");
            check(TarVerifier.verify(archive, new HashSet<>(Arrays.asList("marker"))) == 1);
            try (RandomAccessFile file = new RandomAccessFile(archive, "rw")) { file.setLength(600); }
            boolean rejected = false; try { TarVerifier.verify(archive, new HashSet<>(Arrays.asList("marker"))); } catch (IOException expected) { rejected = true; }
            check(rejected);
        } finally {
            for (File file : fixture.listFiles()) Files.delete(file.toPath()); Files.delete(fixture.toPath());
        }
        System.out.println("CHECKS=" + checks + " RESULT=success");
    }
}
