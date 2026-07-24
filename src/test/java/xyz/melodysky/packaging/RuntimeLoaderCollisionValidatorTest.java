package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeLoaderCollisionValidatorTest {
    @TempDir
    Path temp;

    @Test
    void rejectsReservedBaseLoaderEntry() throws Exception {
        Path input = writeJar(Map.of("native0/Loader.class", new byte[] {1}));

        var diagnostics = new RuntimeLoaderCollisionValidator().validate(
                input,
                RuntimeLoaderPlan.create("native0", false));

        assertEquals(1, diagnostics.size());
        assertEquals(
                PackagingDiagnostics.GENERATED_RUNTIME_LOADER_ENTRY_COLLISION,
                diagnostics.get(0).code());
        assertTrue(diagnostics.get(0).message().contains("native0/Loader.class"));
    }

    @Test
    void rejectsMultiReleaseLoaderShadow() throws Exception {
        Path input = writeJar(Map.of(
                "pkg/Foo.class", new byte[] {1},
                "META-INF/versions/17/native0/Loader.class", new byte[] {2}));

        var diagnostics = new RuntimeLoaderCollisionValidator().validate(
                input,
                RuntimeLoaderPlan.create("native0", false));

        assertEquals(1, diagnostics.size());
        assertEquals(
                PackagingDiagnostics.GENERATED_RUNTIME_LOADER_VERSIONED_SHADOW,
                diagnostics.get(0).code());
        assertTrue(diagnostics.get(0).message().contains(
                "META-INF/versions/17/native0/Loader.class"));
    }

    @Test
    void acceptsUnrelatedClassesAndVersionedEntries() throws Exception {
        Path input = writeJar(Map.of(
                "pkg/Foo.class", new byte[] {1},
                "META-INF/versions/17/pkg/Foo.class", new byte[] {2},
                "META-INF/versions/17/other/native0/Loader.class", new byte[] {3}));

        assertTrue(new RuntimeLoaderCollisionValidator()
                .validate(input, RuntimeLoaderPlan.create("native0", false))
                .isEmpty());
    }

    private Path writeJar(Map<String, byte[]> entries) throws Exception {
        Path input = temp.resolve("input-" + entries.hashCode() + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(input))) {
            for (Map.Entry<String, byte[]> entry : new LinkedHashMap<>(entries).entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0L);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return input;
    }
}
