package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NativeSourceNameTest {
    @Test
    void hashSuffixSeparatesOwnersWithTheSameReadablePrefix() {
        String dollar = NativeSourceName.llvmFileName("pkg/A$B");
        String underscore = NativeSourceName.llvmFileName("pkg/A_B");

        assertNotEquals(dollar, underscore);
        assertTrue(dollar.endsWith(".ll"));
        assertTrue(underscore.endsWith(".ll"));
    }

    @Test
    void capsLongOwnerPrefixForPortableFileSystems() {
        String fileName = NativeSourceName.llvmFileName("pkg/" + "VeryLongOwner".repeat(40));

        assertTrue(fileName.length() <= 101);
    }
}
