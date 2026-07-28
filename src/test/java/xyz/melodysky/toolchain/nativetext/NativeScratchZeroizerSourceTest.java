package xyz.melodysky.toolchain.nativetext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NativeScratchZeroizerSourceTest {
    @Test
    void emitsPortableVolatileByteStores() {
        String source = new NativeScratchZeroizerSource().emit();

        assertTrue(source.contains("volatile unsigned char* cursor"));
        assertTrue(source.contains("*cursor++ = 0u;"));
        assertTrue(source.contains("length--;"));
        assertFalse(source.contains("memset"));
        assertFalse(source.contains("SecureZeroMemory"));
        assertFalse(source.contains("explicit_bzero"));
    }
}
