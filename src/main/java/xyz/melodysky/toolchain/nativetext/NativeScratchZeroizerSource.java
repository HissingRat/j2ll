package xyz.melodysky.toolchain.nativetext;

/**
 * Emits a cross-target zeroizer whose volatile byte stores cannot be removed
 * as an ordinary dead memset.
 */
public final class NativeScratchZeroizerSource {
    public static final String FUNCTION_NAME = "j2ll_native_text_zero";

    public String emit() {
        return """
                static void j2ll_native_text_zero(void* memory, size_t length) {
                    volatile unsigned char* cursor = (volatile unsigned char*)memory;
                    while (length != 0u) {
                        *cursor++ = 0u;
                        length--;
                    }
                }

                """;
    }
}
