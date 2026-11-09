import com.pico4.regionswitch.SwitchTransaction;
import java.util.*;

public class SwitchTransactionTest {
    static int assertions;
    static void check(boolean value) { assertions++; if (!value) throw new AssertionError(); }
    static final class Driver implements SwitchTransaction.Actions {
        final List<String> events = new ArrayList<>();
        final Set<String> fail = new HashSet<>();
        Driver(String... failures) { fail.addAll(Arrays.asList(failures)); }
        public void step(String name) throws Exception {
            events.add(name);
            if (fail.remove(name)) throw new Exception("injected " + name);
        }
        void execute() { try { SwitchTransaction.run(this); } catch (Exception expected) {} }
        int index(String event) { return events.indexOf(event); }
    }
    public static void main(String[] args) throws Exception {
        Driver normal = new Driver();
        SwitchTransaction.run(normal);
        check(normal.index("current-target") < normal.index("clear-journal"));
        check(!normal.events.contains("park-target"));
        for (String stage : new String[]{"activate-data", "set-target-region", "install-target", "verify-target-data",
                "resume-target", "verify-backup", "verify-target-health", "current-target", "complete", "clear-journal"}) {
            Driver failed = new Driver(stage); failed.execute();
            check(failed.events.contains("current-source"));
            check(failed.index("park-target") < failed.index("install-source-scratch"));
            check(failed.index("install-source-scratch") < failed.index("restore-source-data"));
            check(failed.index("restore-source-data") < failed.index("install-source-restored"));
            check(failed.index("verify-source-health") < failed.index("current-source"));
            check(failed.events.get(failed.events.size() - 1).equals("finish"));
        }
        for (String recovery : new String[]{"stop-target", "park-target", "set-source-region", "install-source-scratch",
                "stop-source-again", "restore-source-data", "install-source-restored", "verify-source-data",
                "resume-source", "verify-source-health", "current-source"}) {
            Driver failed = new Driver("install-target", recovery); failed.execute();
            check(!failed.events.contains("clear-journal"));
            check(!failed.events.contains("finish"));
        }
        Driver early = new Driver("snapshot"); early.execute();
        check(early.events.contains("resume-source"));
        check(!early.events.contains("park-target"));
        Driver preflight = new Driver("preflight"); preflight.execute();
        check(!preflight.events.contains("stop-source"));
        System.out.println("ASSERTIONS=" + assertions + " RESULT=success");
    }
}
