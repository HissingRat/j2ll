package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import xyz.melodysky.toolchain.TargetTriple;

class EmbeddedLibraryLayoutTest {
    @Test
    void mapsSelectedTargetToJarPath() {
        assertEquals(
                "native0/x64-linux.so",
                new EmbeddedLibraryLayout().jarPath("native0", TargetTriple.LINUX_X64));
    }
}
