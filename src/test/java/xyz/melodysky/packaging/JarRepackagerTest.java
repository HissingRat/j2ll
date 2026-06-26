package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarRepackagerTest {
    @TempDir
    Path tempDir;

    @Test
    void preservesManifestResourcesServicesModuleInfoAndMultiReleaseEntries() throws IOException {
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("out").resolve("input.jar");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", bytes("""
                Manifest-Version: 1.0\r
                Main-Class: pkg.Main\r
                Multi-Release: true\r
                \r
                """));
        entries.put("pkg/Foo.class", new byte[] {1, 2, 3});
        entries.put("META-INF/services/pkg.Service", bytes("pkg.Provider\n"));
        entries.put("module-info.class", new byte[] {4, 5, 6});
        entries.put("META-INF/versions/9/pkg/Foo.class", new byte[] {7, 8, 9});
        entries.put("assets/data.bin", new byte[] {10, 11, 12});
        writeJar(inputJar, entries);

        new JarRepackager().write(inputJar, outputJar, Map.of("pkg/Foo.class", new byte[] {42}));

        assertArrayEquals(new byte[] {42}, readEntry(outputJar, "pkg/Foo.class"));
        assertArrayEquals(entries.get("META-INF/MANIFEST.MF"), readEntry(outputJar, "META-INF/MANIFEST.MF"));
        assertArrayEquals(entries.get("META-INF/services/pkg.Service"), readEntry(outputJar, "META-INF/services/pkg.Service"));
        assertArrayEquals(entries.get("module-info.class"), readEntry(outputJar, "module-info.class"));
        assertArrayEquals(entries.get("META-INF/versions/9/pkg/Foo.class"), readEntry(outputJar, "META-INF/versions/9/pkg/Foo.class"));
        assertArrayEquals(entries.get("assets/data.bin"), readEntry(outputJar, "assets/data.bin"));
        try (JarFile jarFile = new JarFile(outputJar.toFile())) {
            assertEquals(entries.size(), jarFile.stream().filter(entry -> !entry.isDirectory()).count());
        }
    }

    @Test
    void doesNotAddPlainGeneratedFallbackClassEntries() throws IOException {
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("out").resolve("input.jar");
        writeJar(inputJar, Map.of("pkg/Foo.class", new byte[] {1, 2, 3}));

        new JarRepackager().write(inputJar, outputJar, Map.of(
                "pkg/Foo.class", new byte[] {42},
                "j2ll/generated/fallback/pkg_Foo/Fallback.class", new byte[] {9, 9, 9}));

        try (JarFile jarFile = new JarFile(outputJar.toFile())) {
            assertNull(jarFile.getJarEntry("j2ll/generated/fallback/pkg_Foo/Fallback.class"));
        }
    }

    @Test
    void addsGeneratedLoaderAndNativeResourceEntries() throws IOException {
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("out").resolve("input.jar");
        writeJar(inputJar, Map.of("pkg/Foo.class", new byte[] {1, 2, 3}));

        new JarRepackager().write(
                inputJar,
                outputJar,
                Map.of("pkg/Foo.class", new byte[] {42}),
                Map.of(
                        "j2ll/generated/seed/NativeLoader.class", new byte[] {5, 6, 7},
                        "native0/arm64-macos.dylib", new byte[] {8, 9},
                        "j2ll/generated/fallback/pkg_Foo/Fallback.class", new byte[] {10}));

        assertArrayEquals(new byte[] {42}, readEntry(outputJar, "pkg/Foo.class"));
        assertArrayEquals(new byte[] {5, 6, 7}, readEntry(outputJar, "j2ll/generated/seed/NativeLoader.class"));
        assertArrayEquals(new byte[] {8, 9}, readEntry(outputJar, "native0/arm64-macos.dylib"));
        try (JarFile jarFile = new JarFile(outputJar.toFile())) {
            assertNull(jarFile.getJarEntry("j2ll/generated/fallback/pkg_Foo/Fallback.class"));
            assertNotNull(jarFile.getJarEntry("j2ll/generated/seed/NativeLoader.class"));
        }
    }

    private void writeJar(Path path, Map<String, byte[]> entries) throws IOException {
        java.nio.file.Files.createDirectories(path.getParent());
        try (JarOutputStream output = new JarOutputStream(java.nio.file.Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0L);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private byte[] readEntry(Path jar, String entryName) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            try (java.io.InputStream input = jarFile.getInputStream(jarFile.getJarEntry(entryName))) {
                return input.readAllBytes();
            }
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
