package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static xyz.melodysky.testsupport.NativeFieldInternalizationFixtures.nativeStored;
import static xyz.melodysky.testsupport.NativeFieldInternalizationFixtures.plan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.analysis.field.FieldId;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;

class ArtifactAuditInternalizedFieldTest implements Opcodes {
    private static final FieldId APPROVED = new FieldId("pkg/State", "counter", "I");

    @TempDir
    Path temp;

    @Test
    void overloadAddsPassingCheckForCleanJarAndBlockingCheckForResidual() throws Exception {
        ArtifactAudit audit = basePassingAudit();
        Path clean = writeJar("clean.jar", classBytes(false));
        Path residual = writeJar("residual.jar", classBytes(true));

        ArtifactAuditResult cleanResult = audit.audit(
                temp,
                clean,
                "native0",
                List.of(),
                List.of(),
                List.of(),
                approvedPlan());
        ArtifactAuditResult residualResult = audit.audit(
                temp,
                residual,
                "native0",
                List.of(),
                List.of(),
                List.of(),
                approvedPlan());

        assertTrue(cleanResult.passed(), cleanResult.checks().toString());
        assertTrue(cleanResult.checks().stream().anyMatch(check ->
                check.name().equals("jar.internalizedFieldResiduals")
                        && check.reasonCode().equals("INTERNALIZED_FIELDS_REMOVED")
                        && check.status().equals("passed")));
        assertFalse(residualResult.passed());
        assertTrue(residualResult.checks().stream().anyMatch(check ->
                check.name().equals("jar.internalizedFieldResiduals")
                        && check.reasonCode().equals("INTERNALIZED_FIELD_RESIDUAL")
                        && check.status().equals("failed")));
    }

    private ArtifactAudit basePassingAudit() {
        return new ArtifactAudit() {
            @Override
            public ArtifactAuditResult audit(
                    Path workspaceRoot,
                    Path outputJar,
                    String embeddedLibraryDirectory,
                    List<EmbeddedLibraryReport> embeddedLibraries,
                    List<String> exportedSymbols,
                    List<SensitivePlaintextFact> sensitivePlaintextFacts) {
                return new ArtifactAuditResult(
                        true,
                        List.of(ArtifactAuditCheck.passed(
                                "base",
                                "BASE_AUDIT_PASSED",
                                "test fixture base audit passed")));
            }
        };
    }

    private Path writeJar(String fileName, byte[] classBytes) throws Exception {
        Path jar = temp.resolve(fileName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            JarEntry entry = new JarEntry("pkg/State.class");
            entry.setTime(0L);
            output.putNextEntry(entry);
            output.write(classBytes);
            output.closeEntry();
        }
        return jar;
    }

    private byte[] classBytes(boolean includeApprovedField) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, APPROVED.owner(), null, "java/lang/Object", null);
        if (includeApprovedField) {
            writer.visitField(
                            ACC_PRIVATE | ACC_STATIC,
                            APPROVED.name(),
                            APPROVED.descriptor(),
                            null,
                            null)
                    .visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private NativeFieldInternalizationPlan approvedPlan() {
        return plan(List.of(nativeStored(
                APPROVED,
                "j2ll_nfs_00112233445566778899aabbccddeeff",
                List.of())));
    }
}
