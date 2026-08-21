package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class InterfaceMethodHelperCollisionValidatorTest {
    @TempDir
    Path temp;

    @Test
    void rejectsFinalImplementedHelperBaseEntryCollision() throws Exception {
        MethodRewriteDecision decision = defaultMethodDecision();
        Path inputJar = jar(Map.of(
                decision.registrationOwner() + ".class",
                new byte[] {1}));

        var diagnostics = new InterfaceMethodHelperCollisionValidator()
                .validate(inputJar, List.of(decision));

        assertEquals(1, diagnostics.size());
        assertEquals(
                PackagingDiagnostics.GENERATED_INTERFACE_HELPER_ENTRY_COLLISION,
                diagnostics.get(0).code());
    }

    @Test
    void rejectsFinalImplementedHelperMultiReleaseShadow() throws Exception {
        MethodRewriteDecision decision = defaultMethodDecision();
        Path inputJar = jar(Map.of(
                "META-INF/versions/17/"
                        + decision.registrationOwner()
                        + ".class",
                new byte[] {1}));

        var diagnostics = new InterfaceMethodHelperCollisionValidator()
                .validate(inputJar, List.of(decision));

        assertEquals(1, diagnostics.size());
        assertEquals(
                PackagingDiagnostics.GENERATED_INTERFACE_HELPER_VERSIONED_SHADOW,
                diagnostics.get(0).code());
    }

    @Test
    void acceptsFinalImplementedHelperWhenReservedEntriesAreAbsent() throws Exception {
        MethodRewriteDecision decision = defaultMethodDecision();
        Path inputJar = jar(Map.of(
                "pkg/Api.class",
                AsmFixtureBuilder.interfaceWithAbstractAndDefault("pkg/Api"),
                "META-INF/versions/17/pkg/Other.class",
                new byte[] {1}));

        var diagnostics = new InterfaceMethodHelperCollisionValidator()
                .validate(inputJar, List.of(decision));

        assertTrue(diagnostics.isEmpty(), diagnostics.toString());
    }

    private MethodRewriteDecision defaultMethodDecision() {
        var parsed = new AsmClassParser()
                .parse(new ClassFileEntry(
                        "pkg/Api.class",
                        AsmFixtureBuilder.interfaceWithAbstractAndDefault("pkg/Api"),
                        "fixture"))
                .artifact()
                .orElseThrow();
        return new MethodRewritePlanner().planClass(parsed, 0x6a326c6cL).stream()
                .filter(decision -> decision.method().name().equals("answer"))
                .findFirst()
                .orElseThrow();
    }

    private Path jar(Map<String, byte[]> entries) throws IOException {
        Path jar = temp.resolve("input.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var entry : new LinkedHashMap<>(entries).entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0L);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }
}
