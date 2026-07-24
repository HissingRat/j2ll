package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import xyz.melodysky.config.SignaturePolicy;

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
                        "native0/Loader.class", new byte[] {5, 6, 7},
                        "native0/arm64-macos.dylib", new byte[] {8, 9},
                        "j2ll/generated/fallback/pkg_Foo/Fallback.class", new byte[] {10}));

        assertArrayEquals(new byte[] {42}, readEntry(outputJar, "pkg/Foo.class"));
        assertArrayEquals(new byte[] {5, 6, 7}, readEntry(outputJar, "native0/Loader.class"));
        assertArrayEquals(new byte[] {8, 9}, readEntry(outputJar, "native0/arm64-macos.dylib"));
        try (JarFile jarFile = new JarFile(outputJar.toFile())) {
            assertNull(jarFile.getJarEntry("j2ll/generated/fallback/pkg_Foo/Fallback.class"));
            assertNotNull(jarFile.getJarEntry("native0/Loader.class"));
        }
    }

    @Test
    void rejectsAddedEntryThatCollidesWithInput() throws IOException {
        Path inputJar = tempDir.resolve("input.jar");
        Path outputJar = tempDir.resolve("out").resolve("input.jar");
        writeJar(inputJar, Map.of("native0/Loader.class", new byte[] {1, 2, 3}));

        IOException error = assertThrows(
                IOException.class,
                () -> new JarRepackager().write(
                        inputJar,
                        outputJar,
                        Map.of(),
                        Map.of("native0/Loader.class", new byte[] {4, 5, 6})));

        assertTrue(error.getMessage().contains("native0/Loader.class"), error.getMessage());
    }

    @Test
    void stripsSignatureFilesWhenPolicyIsStrip() throws IOException {
        Path inputJar = tempDir.resolve("signed.jar");
        Path outputJar = tempDir.resolve("out").resolve("signed.jar");
        writeJar(inputJar, Map.of(
                "META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\r\n\r\n"),
                "META-INF/TEST.SF", bytes("Signature-Version: 1.0\r\n\r\n"),
                "META-INF/TEST.RSA", new byte[] {1, 2, 3},
                "pkg/Foo.class", new byte[] {4, 5, 6}));

        JarRepackager repackager = new JarRepackager();
        SignatureActionReport action = repackager.inspectSignature(inputJar, SignaturePolicy.STRIP);
        repackager.write(inputJar, outputJar, Map.of(), Map.of(), SignaturePolicy.STRIP);

        assertEquals("strip", action.action());
        assertEquals(2, action.removedEntries().size());
        assertArrayEquals(bytes("Manifest-Version: 1.0\r\n\r\n"), readEntry(outputJar, "META-INF/MANIFEST.MF"));
        assertArrayEquals(new byte[] {4, 5, 6}, readEntry(outputJar, "pkg/Foo.class"));
        try (JarFile jarFile = new JarFile(outputJar.toFile())) {
            assertNull(jarFile.getJarEntry("META-INF/TEST.SF"));
            assertNull(jarFile.getJarEntry("META-INF/TEST.RSA"));
        }
    }

    @Test
    void reportsSignedInputForFailPolicy() throws IOException {
        Path inputJar = tempDir.resolve("signed.jar");
        writeJar(inputJar, Map.of(
                "META-INF/TEST.SF", bytes("Signature-Version: 1.0\r\n\r\n"),
                "pkg/Foo.class", new byte[] {4, 5, 6}));

        SignatureActionReport action = new JarRepackager().inspectSignature(inputJar, SignaturePolicy.FAIL);

        assertEquals("fail", action.action());
        assertEquals("SIGNED_INPUT_REJECTED", action.reasonCode());
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
