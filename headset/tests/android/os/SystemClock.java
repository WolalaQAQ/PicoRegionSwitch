package android.os;

/** Host test substitute only; never included in the Android APK. */
public final class SystemClock {
    public static long elapsedRealtime() { return System.nanoTime() / 1000000L; }
    public static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
    }
}
