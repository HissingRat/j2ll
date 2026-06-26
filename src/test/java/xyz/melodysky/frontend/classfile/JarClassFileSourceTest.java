package xyz.melodysky.frontend.classfile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class JarClassFileSourceTest {
    @TempDir
    Path tempDir;

    @Test
    void readsOnlyClassEntriesInStableOrder() throws IOException {
        byte[] alpha = AsmFixtureBuilder.minimalClass("pkg/Alpha");
        byte[] zed = AsmFixtureBuilder.minimalClass("pkg/Zed");
        Path jarPath = tempDir.resolve("fixtures.jar");
        writeJar(jarPath, Map.of(
                "pkg/Zed.class", zed,
                "resource.txt", "hello".getBytes(),
                "pkg/Alpha.class", alpha));

        List<ClassFileEntry> entries = new JarClassFileSource(jarPath).entries();

        assertEquals(List.of("pkg/Alpha.class", "pkg/Zed.class"), entries.stream().map(ClassFileEntry::entryName).toList());
        assertArrayEquals(alpha, entries.get(0).bytes());
        assertArrayEquals(zed, entries.get(1).bytes());
    }

    private void writeJar(Path jarPath, Map<String, byte[]> entries) throws IOException {
        try (JarOutputStream output = new JarOutputStream(java.nio.file.Files.newOutputStream(jarPath))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }
}
