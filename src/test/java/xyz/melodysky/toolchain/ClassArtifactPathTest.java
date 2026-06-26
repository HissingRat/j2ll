package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClassArtifactPathTest {
    @Test
    void classDirectoryUsesSafePathAndHashPrefix() {
        String path = new ClassArtifactPath().classDirectory("com/example/Foo$Bar");

        assertTrue(path.startsWith("com/example/Foo$Bar__"));
        assertEquals(16, path.substring(path.lastIndexOf("__") + 2).length());
    }

    @Test
    void methodIdHandlesConstructorsAndDescriptors() {
        String methodId = new ClassArtifactPath().methodId("pkg/Foo", "<init>", "(I)V");

        assertTrue(methodId.startsWith("_init___"));
        assertEquals(16, methodId.substring(methodId.lastIndexOf("__") + 2).length());
    }

    @Test
    void unicodeSegmentsAreEscaped() {
        String path = new ClassArtifactPath().classDirectory("pkg/类");

        assertTrue(path.startsWith("pkg/_u7c7b___"));
    }

    @Test
    void reservedAndTrailingDotSegmentsAreMadeFilesystemSafe() {
        ClassArtifactPath path = new ClassArtifactPath();

        assertTrue(path.safeInternalName("pkg/CON").endsWith("_CON"));
        assertTrue(path.safeInternalName("pkg/Foo.").endsWith("Foo._"));
    }
}
