package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.analysis.method.NativeMethodId;
import xyz.melodysky.analysis.method.NativeMethodInternalizationDecision;
import xyz.melodysky.analysis.method.NativeMethodInternalizationPlan;
import xyz.melodysky.analysis.method.NativeMethodInternalizationReason;
import xyz.melodysky.analysis.method.NativeMethodInternalizationStatus;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;

class InternalizedMethodArtifactVerifierTest implements Opcodes {
    private static final NativeMethodId APPROVED =
            new NativeMethodId("pkg/Secret", "hidden", "()V");
    private static final Handle APPROVED_HANDLE = new Handle(
            H_INVOKESTATIC,
            APPROVED.owner(),
            APPROVED.name(),
            APPROVED.descriptor(),
            false);
    private static final Handle BENIGN_BOOTSTRAP = new Handle(
            H_INVOKESTATIC,
            "pkg/Bootstrap",
            "bootstrap",
            "(Ljava/lang/invoke/MethodHandles$Lookup;"
                    + "Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;",
            false);

    @TempDir
    Path temp;

    @Test
    void cleanJarHasNoResiduals() throws Exception {
        Path jarPath = writeJar(
                "clean.jar",
                Map.of("pkg/Clean.class", cleanClassBytes()));

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertEquals(
                    List.of(),
                    new InternalizedMethodArtifactVerifier()
                            .residuals(jar, approvedPlan()));
        }
    }

    @Test
    void reportsEveryMethodCarrierWithStablePrivacySafeFindings()
            throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pkg/Inner.class", enclosingMethodClassBytes());
        entries.put("pkg/Caller.class", referenceClassBytes());
        entries.put("pkg/Secret.class", declaringClassBytes());
        Path jarPath = writeJar("residual.jar", entries);

        List<String> findings;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            findings = new InternalizedMethodArtifactVerifier()
                    .residuals(jar, approvedPlan());
        }

        assertEquals(
                findings.stream().sorted().distinct().toList(),
                findings);
        assertContainsKind(findings, "declaration");
        assertContainsKind(findings, "methodInstruction");
        assertContainsKind(findings, "ldcHandle");
        assertContainsKind(findings, "invokedynamicBootstrap");
        assertContainsKind(findings, "invokedynamicArgument");
        assertContainsKind(findings, "constantDynamicBootstrap");
        assertContainsKind(findings, "constantDynamicArgument");
        assertContainsKind(findings, "enclosingMethod");
        assertTrue(findings.stream().allMatch(finding -> finding.matches(
                "locationHash=[0-9a-f]{64} kind=[A-Za-z]+ "
                        + "methodIdHash=[0-9a-f]{64}")));
        assertFalse(findings.stream().anyMatch(finding ->
                finding.contains(APPROVED.owner())
                        || finding.contains(APPROVED.name())
                        || finding.contains("pkg/Caller")));
    }

    private void assertContainsKind(List<String> findings, String kind) {
        assertTrue(
                findings.stream()
                        .anyMatch(finding ->
                                finding.contains(" kind=" + kind + " ")),
                () -> "missing " + kind + " in " + findings);
    }

    private Path writeJar(
            String fileName,
            Map<String, byte[]> entries) throws Exception {
        Path jar = temp.resolve(fileName);
        try (JarOutputStream output =
                new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0L);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }

    private byte[] cleanClassBytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, "pkg/Clean", null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] declaringClassBytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                V17,
                ACC_PUBLIC,
                APPROVED.owner(),
                null,
                "java/lang/Object",
                null);
        MethodVisitor method = writer.visitMethod(
                ACC_PRIVATE | ACC_STATIC,
                APPROVED.name(),
                APPROVED.descriptor(),
                null,
                null);
        method.visitCode();
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] referenceClassBytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                V17,
                ACC_PUBLIC,
                "pkg/Caller",
                null,
                "java/lang/Object",
                null);
        MethodVisitor method =
                writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(
                INVOKESTATIC,
                APPROVED.owner(),
                APPROVED.name(),
                APPROVED.descriptor(),
                false);
        method.visitLdcInsn(APPROVED_HANDLE);
        method.visitInsn(POP);
        method.visitInvokeDynamicInsn(
                "bootstrapReference",
                "()V",
                APPROVED_HANDLE);
        method.visitInvokeDynamicInsn(
                "argumentReference",
                "()V",
                BENIGN_BOOTSTRAP,
                APPROVED_HANDLE);
        ConstantDynamic nested = new ConstantDynamic(
                "nested",
                "Ljava/lang/Object;",
                APPROVED_HANDLE,
                APPROVED_HANDLE);
        ConstantDynamic outer = new ConstantDynamic(
                "outer",
                "Ljava/lang/Object;",
                BENIGN_BOOTSTRAP,
                nested);
        method.visitLdcInsn(outer);
        method.visitInsn(POP);
        method.visitInsn(RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] enclosingMethodClassBytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                V17,
                ACC_PUBLIC,
                "pkg/Inner",
                null,
                "java/lang/Object",
                null);
        writer.visitOuterClass(
                APPROVED.owner(),
                APPROVED.name(),
                APPROVED.descriptor());
        writer.visitEnd();
        return writer.toByteArray();
    }

    private NativeMethodInternalizationPlan approvedPlan() {
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
