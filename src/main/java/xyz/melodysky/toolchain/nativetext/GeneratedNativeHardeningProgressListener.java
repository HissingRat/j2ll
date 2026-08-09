package xyz.melodysky.toolchain.nativetext;

/** Receives real completed/total units from the generated-C hardening audit. */
@FunctionalInterface
public interface GeneratedNativeHardeningProgressListener {
    void progress(long completed, long total, String detail);

    static GeneratedNativeHardeningProgressListener none() {
        return (completed, total, detail) -> {
        };
    }
}
