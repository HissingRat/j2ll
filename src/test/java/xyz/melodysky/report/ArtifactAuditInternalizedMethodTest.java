package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.analysis.method.NativeMethodId;
import xyz.melodysky.analysis.method.NativeMethodInternalizationDecision;
import xyz.melodysky.analysis.method.NativeMethodInternalizationPlan;
import xyz.melodysky.analysis.method.NativeMethodInternalizationReason;
import xyz.melodysky.analysis.method.NativeMethodInternalizationStatus;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;

class ArtifactAuditInternalizedMethodTest implements Opcodes {
    private static final NativeMethodId APPROVED =
            new NativeMethodId("pkg/Worker", "nativeOnly", "()I");

    @TempDir
    Path temp;

    @Test
    void overloadAddsPassingCheckForCleanJarAndBlockingCheckForResidual()
            throws Exception {
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
                emptyFieldPlan(),
                approvedMethodPlan());
        ArtifactAuditResult residualResult = audit.audit(
                temp,
                residual,
                "native0",
                List.of(),
                List.of(),
                List.of(),
                emptyFieldPlan(),
                approvedMethodPlan());

        assertTrue(cleanResult.passed(), cleanResult.checks().toString());
        assertTrue(cleanResult.checks().stream().anyMatch(check ->
                check.name().equals("jar.internalizedMethodResiduals")
                        && check.reasonCode()
                                .equals("INTERNALIZED_METHODS_REMOVED")
                        && check.status().equals("passed")));
        assertFalse(residualResult.passed());
        assertTrue(residualResult.checks().stream().anyMatch(check ->
                check.name().equals("jar.internalizedMethodResiduals")
                        && check.reasonCode()
                                .equals("INTERNALIZED_METHOD_RESIDUAL")
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
        try (JarOutputStream output =
                new JarOutputStream(Files.newOutputStream(jar))) {
            JarEntry entry = new JarEntry("pkg/Worker.class");
            entry.setTime(0L);
            output.putNextEntry(entry);
            output.write(classBytes);
            output.closeEntry();
        }
        return jar;
    }

    private byte[] classBytes(boolean includeApprovedMethod) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                V17,
                ACC_PUBLIC,
                APPROVED.owner(),
                null,
                "java/lang/Object",
                null);
        if (includeApprovedMethod) {
            MethodVisitor method = writer.visitMethod(
                    ACC_PRIVATE | ACC_STATIC,
                    APPROVED.name(),
                    APPROVED.descriptor(),
                    null,
                    null);
            method.visitCode();
            method.visitInsn(ICONST_1);
            method.visitInsn(IRETURN);
            method.visitMaxs(1, 0);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private NativeFieldInternalizationPlan emptyFieldPlan() {
        return NativeFieldInternalizationPlan.empty();
    }

    private NativeMethodInternalizationPlan approvedMethodPlan() {
        return new NativeMethodInternalizationPlan(
                true,
                WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                List.of(new NativeMethodInternalizationDecision(
                        APPROVED,
                        NativeMethodInternalizationStatus.INTERNALIZED,
                        true,
                        "private",
                        List.of("pkg/Caller#run!()V"),
                        List.of(NativeMethodInternalizationReason
                                .METHOD_INTERNALIZATION_ELIGIBLE))));
    }
}
