package android.os;

/** Compile-only Android hidden API signature and host-test replacement. */
public final class SystemProperties {
    public static String value;

    private SystemProperties() {}

    public static long getLong(String key, long fallback) {
        if (!"sys.pico_region_downgrade_until".equals(key)) {
            throw new AssertionError(key);
        }
        if (value == null) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}
