package xyz.melodysky.toolchain.nativetext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NativeScratchZeroizerSourceTest {
    @Test
    void emitsPortableVolatileByteStores() {
        String source = new NativeScratchZeroizerSource().emit();

        assertTrue(source.contains(
                "static __attribute__((noinline)) void "
                        + NativeScratchZeroizerSource.FUNCTION_NAME));
        assertTrue(source.contains("volatile unsigned char* cursor"));
        assertTrue(source.contains("*cursor++ = 0u;"));
        assertTrue(source.contains("length--;"));
        assertTrue(source.contains(
                "static __attribute__((noinline, unused)) void "
                        + NativeScratchZeroizerSource
                                .CLEANUP_FUNCTION_NAME));
        assertTrue(source.contains(
                "const size_t length = *((const size_t*)memory);"));
        assertTrue(source.contains(
                "j2ll_native_text_zero(\n"
                        + "            (unsigned char*)memory"
                        + " + sizeof(size_t),\n"
                        + "            length);"));
        assertFalse(source.contains("memset"));
        assertFalse(source.contains("SecureZeroMemory"));
        assertFalse(source.contains("explicit_bzero"));
    }
}
