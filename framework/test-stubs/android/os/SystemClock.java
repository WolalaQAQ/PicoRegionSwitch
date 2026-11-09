package android.os;

/** Host-test replacement, never included in the framework dex. */
public final class SystemClock {
    public static long now;

    private SystemClock() {}

    public static long elapsedRealtime() {
        return now;
    }
}
