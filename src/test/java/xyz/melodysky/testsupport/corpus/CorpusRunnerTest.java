package xyz.melodysky.testsupport.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class CorpusRunnerTest implements Opcodes {
    @TempDir
    Path temp;

    @Test
    void runsDeterministicMinimalStaticAddCorpus() throws Exception {
        CorpusRunResult result = new CorpusRunner().run(new CorpusCase(
                "minimal-static-add",
                "pkg.CorpusMain",
                Map.of(
                        "pkg/CorpusMath.class", AsmFixtureBuilder.classWithAddMethod("pkg/CorpusMath"),
                        "pkg/CorpusMain.class", minimalMainClass()),
                List.of("pkg/CorpusMath#add!(II)I"),
                false), temp);

        assertTrue(result.pipelineResult().successful(), result.pipelineResult().diagnostics().toString());
        assertTrue(result.normalizedOutputMatches(), result.outputRun().stderr());
        assertEquals("7\n", result.outputRun().stdout());
        assertReportSet(result);
        assertTrue(Files.readString(result.reportPaths().reports().get("release-readiness.json"))
                .contains("\"status\": \"passed\""));
        assertTrue(Files.readString(result.reportPaths().reports().get("lowering-report.json"))
                .contains("\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
    }

    @Test
    void runsMixedFeatureCorpusWithHelpersSkippedMethodAndProtectionReports() throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("pkg/CorpusMath.class", AsmFixtureBuilder.classWithAddMethod("pkg/CorpusMath"));
        entries.put("pkg/StringBuilderOps.class", AsmFixtureBuilder.classWithJdkStringBuilderMethods("pkg/StringBuilderOps"));
        entries.put("pkg/JdkFallback.class", AsmFixtureBuilder.classWithUnsupportedJdkStringCall("pkg/JdkFallback"));
        entries.put("pkg/MixedCorpusMain.class", mixedMainClass());

        CorpusRunResult result = new CorpusRunner().run(new CorpusCase(
                "mixed-release-corpus",
                "pkg.MixedCorpusMain",
                entries,
                List.of(
                        "pkg/CorpusMath#add!(II)I",
                        "pkg/StringBuilderOps#build!(Ljava/lang/String;I)Ljava/lang/String;",
                        "pkg/JdkFallback#substring!(Ljava/lang/String;)Ljava/lang/String;"),
                true), temp);

        assertTrue(result.pipelineResult().successful(), result.pipelineResult().diagnostics().toString());
        assertTrue(result.normalizedOutputMatches(), result.outputRun().stderr());
        assertEquals("""
                7
                v3
                bc
                """, result.outputRun().stdout());
        assertReportSet(result);
        String loweringReport = Files.readString(result.reportPaths().reports().get("lowering-report.json"));
        String packagingReport = Files.readString(result.reportPaths().reports().get("packaging-report.json"));
        String protectionReport = Files.readString(result.reportPaths().reports().get("protection-report.json"));
        assertTrue(loweringReport.contains("\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(loweringReport.contains("\"status\": \"skipped\""), loweringReport);
        assertTrue(loweringReport.contains("\"nativeImplementationPath\": null"), loweringReport);
        assertTrue(!packagingReport.contains("\"fallbackBlobs\""), packagingReport);
        assertTrue(Files.readString(
                        result.reportPaths().reports().get("skipped-method-report.json"))
                .contains("pkg/JdkFallback#substring!(Ljava/lang/String;)Ljava/lang/String;"));
        assertTrue(protectionReport.contains("\"seedMode\": \"reproducible\""));
        assertTrue(protectionReport.matches(
                "(?s).*\\\"seedHash\\\": \\\"[0-9a-f]{64}\\\".*"));
        assertTrue(!protectionReport.contains("corpus-seed"), protectionReport);
    }

    private void assertReportSet(CorpusRunResult result) {
        assertEquals(List.of(
                "artifact-audit.json",
                "diagnostics.json",
                "index.json",
                "known-blockers.json",
                "lowering-report.json",
                "opcode-support-matrix.json",
                "packaging-report.json",
                "protection-report.json",
                "release-readiness.json",
                "skipped-method-report.json",
                "summary.json",
                "summary.md",
                "support-matrix.json",
                "symbol-audit.json"), result.reportPaths().reports().keySet().stream().toList());
        result.reportPaths().reports().forEach((name, path) -> assertTrue(Files.isRegularFile(path), name));
    }

    private byte[] minimalMainClass() {
        ClassWriter writer = mainClass("pkg/CorpusMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitInsn(ICONST_2);
        main.visitInsn(ICONST_5);
        main.visitMethodInsn(INVOKESTATIC, "pkg/CorpusMath", "add", "(II)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] mixedMainClass() {
        ClassWriter writer = mainClass("pkg/MixedCorpusMain");
        MethodVisitor main = beginMain(writer);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitInsn(ICONST_2);
        main.visitInsn(ICONST_5);
        main.visitMethodInsn(INVOKESTATIC, "pkg/CorpusMath", "add", "(II)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);

        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("v");
        main.visitInsn(ICONST_3);
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/StringBuilderOps",
                "build",
                "(Ljava/lang/String;I)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("abc");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/JdkFallback",
                "substring",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        endMain(main);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private ClassWriter mainClass(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        return writer;
    }

    private MethodVisitor beginMain(ClassWriter writer) {
        MethodVisitor main = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        return main;
    }

    private void endMain(MethodVisitor main) {
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();
    }
}
