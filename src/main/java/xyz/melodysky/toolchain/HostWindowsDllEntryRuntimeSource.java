package xyz.melodysky.toolchain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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
        if (!NativeLibraryName.isSafe(libraryName)) {
            throw new IllegalArgumentException("unsafe native library name");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("windows-dll-entry\u0000" + libraryName).getBytes(StandardCharsets.UTF_8));
            char[] identifier = new char[32];
            for (int index = 0; index < identifier.length; index++) {
                int value = digest[index / 2] & 0xff;
                int nibble = (index & 1) == 0 ? value >>> 4 : value & 0x0f;
                identifier[index] = (char) ('a' + nibble);
            }
            return new String(identifier);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
