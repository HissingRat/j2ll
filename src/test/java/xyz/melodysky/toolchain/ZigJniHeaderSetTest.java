package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZigJniHeaderSetTest {
    @TempDir
    Path temp;

    @Test
    void writesOneTargetConditionalJniMdHeaderForTheWholeMatrix() throws Exception {
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);

        List<Path> includes = new ZigJniHeaderSet().prepare(workspace);

        Path portableInclude = workspace.jniDirectory().resolve("include");
        String header = Files.readString(portableInclude.resolve("jni_md.h"));
        assertEquals(List.of(portableInclude), includes);
        assertTrue(Files.isRegularFile(portableInclude.resolve("jni.h")));
        assertTrue(header.contains("#if defined(_WIN32)"));
        assertTrue(header.contains("#define JNIEXPORT __declspec(dllexport)"));
        assertTrue(header.contains("#define JNIEXPORT __attribute__((visibility(\"default\")))"));
        assertTrue(header.contains("typedef int jint;"));
        assertFalse(includes.stream().anyMatch(path -> path.endsWith("win32")
                || path.endsWith("linux")
                || path.endsWith("darwin")));
    }
}
