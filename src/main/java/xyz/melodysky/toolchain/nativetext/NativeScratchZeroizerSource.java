package xyz.melodysky.toolchain.nativetext;

/**
 * Emits a cross-target zeroizer whose volatile byte stores cannot be removed
 * as an ordinary dead memset.
 */
public final class NativeScratchZeroizerSource {
    public static final String FUNCTION_NAME = "j2ll_native_text_zero";
    public static final String CLEANUP_FUNCTION_NAME =
            "j2ll_native_text_cleanup";

    public String emit() {
        return """
                static __attribute__((noinline)) void j2ll_native_text_zero(
                        void* memory,
                        size_t length) {
                    volatile unsigned char* cursor = (volatile unsigned char*)memory;
                    while (length != 0u) {
                        *cursor++ = 0u;
                        length--;
                    }
                }

                static __attribute__((noinline, unused)) void j2ll_native_text_cleanup(
                        void* memory) {
                    const size_t length = *((const size_t*)memory);
                    j2ll_native_text_zero(
                            (unsigned char*)memory + sizeof(size_t),
                            length);
                }

                """;
    }
}
