package xyz.melodysky.toolchain;

/** Minimal PE DLL entry used only when the final library intentionally omits the CRT. */
final class HostWindowsDllEntryRuntimeSource {
    String emit(String libraryName) {
        String symbol = symbol(libraryName);
        return """
                #if defined(_WIN32)
                int %s(void* module, unsigned long reason, void* reserved) {
                    (void)module;
                    (void)reason;
                    (void)reserved;
                    return 1;
                }
                #endif
                """.formatted(symbol);
    }

    static String symbol(String libraryName) {
        if (libraryName == null
                || !libraryName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("unsafe native library name");
        }
        return libraryName + "_entry";
    }
}
