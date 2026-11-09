import android.os.SystemClock;
import android.os.SystemProperties;
import com.android.server.pm.PicoRegionDowngradeGate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class GateSemanticsTest {
    private static int assertions;

    private static void expect(String name, String pkg, String value, long now, boolean expected) {
        SystemProperties.value = value;
        SystemClock.now = now;
        boolean actual = PicoRegionDowngradeGate.allow(pkg);
        if (actual != expected) throw new AssertionError(name + ": " + actual);
        assertions++;
    }

    public static void main(String[] args) throws Exception {
        for (String pkg : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            expect("missing", pkg, null, 1000, false);
            expect("empty", pkg, "", 1000, false);
            expect("malformed", pkg, "not-a-long", 1000, false);
            expect("disabled", pkg, "0", 1000, false);
            expect("negative", pkg, "-1", 1000, false);
            expect("expired", pkg, "999", 1000, false);
            expect("equal", pkg, "1000", 1000, false);
            expect("one-ms", pkg, "1001", 1000, true);
            expect("max-window", pkg, "121000", 1000, true);
            expect("too-long", pkg, "121001", 1000, false);
            expect("max-long", pkg, Long.toString(Long.MAX_VALUE), 1000, false);
            expect("min-long", pkg, Long.toString(Long.MIN_VALUE), 1000, false);
            expect("overflow-text", pkg, "9223372036854775808", 1000, false);
            expect("near-max", pkg, Long.toString(Long.MAX_VALUE), Long.MAX_VALUE - 120000, true);
            expect("case-sensitive", pkg.toUpperCase(java.util.Locale.ROOT), "1001", 1000, false);
            expect("prefix", pkg + ".evil", "1001", 1000, false);
            expect("whitespace", " " + pkg, "1001", 1000, false);
        }
        for (String pkg : new String[] {null, "", "android", "com.android.settings",
                "com.picovr.activitycenter", "com.pico.browser"}) {
            expect("not-listed", pkg, "1001", 1000, false);
        }
        System.out.println("ASSERTIONS=" + assertions);
        System.out.println("RESULT=success");
    }
}
