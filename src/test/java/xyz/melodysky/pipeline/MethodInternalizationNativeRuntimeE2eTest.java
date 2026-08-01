package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.analysis.world.WholeProgramAnalysisPolicy;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.JarClassFileSource;
import xyz.melodysky.testsupport.DifferentialHarness;
import xyz.melodysky.testsupport.FakeManagedZig;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.J2llHomeResolver;
import xyz.melodysky.toolchain.TargetTriple;

class MethodInternalizationNativeRuntimeE2eTest
        implements Opcodes {
    @TempDir
    Path temp;

    @Test
    void protectedAndNonFinalExactAuthorizedPublicMethodsRunAfterMethodRemoval()
            throws Exception {
        Path inputJar = temp.resolve(
                "method-internalization.jar");
        LinkedHashMap<String, byte[]> entries =
                new LinkedHashMap<>();
        entries.put(
                "pkg/InternalTargets.class",
                targetsClass());
        entries.put(
                "pkg/InternalCalls.class",
                callsClass());
        entries.put(
                "pkg/InternalMain.class",
                mainClass());
        writeJar(inputJar, entries);
        ResolvedConfig config = config(inputJar);
        Path workspace = temp.resolve("out");

        MainlinePipelineResult pipeline;
        try (AutoCloseable ignored = useManagedZig()) {
            pipeline = new MainlinePipeline().run(
                    config,
                    workspace,
                    xyz.melodysky.progress
                            .BuildProgressListener.none(),
                    WholeProgramAnalysisPolicy.strict(),
                    SkippedMethodApproval.allowAll());
        }
        var differential =
                new DifferentialHarness()
                        .compareOriginalToOutputJar(
                                inputJar,
                                pipeline.outputJar(),
                                "pkg.InternalMain");

        assertTrue(
                pipeline.successful(),
                pipeline.diagnostics().toString());
        if (realToolchainAvailable()) {
            try (var libraries = Files.list(
                    workspace.resolve("native"))) {
                assertEquals(
                        6,
                        libraries.filter(
                                        Files::isRegularFile)
                                .count());
            }
        }
        assertEquals(
                differential.originalRun().stdout(),
                differential.outputRun().stdout());
        assertEquals(
                """
                target-init
                bc
                yz
                vw
                no
                static-caught
                instance-caught
                """,
                differential.outputRun().stdout());
        String lowering = Files.readString(
                workspace.resolve(
                        "reports/lowering-report.json"));
        var outputProgram = new AsmClassParser()
                .parseAll(new JarClassFileSource(
                        pipeline.outputJar()))
                .artifact()
                .orElseThrow()
                .program();
        assertFalse(outputProgram
                .findClass("pkg/InternalTargets")
                .orElseThrow()
                .methods()
                .stream()
                .anyMatch(method ->
                        method.name().equals("hiddenStatic")),
                lowering);
        assertFalse(outputProgram
                .findClass("pkg/InternalTargets")
                .orElseThrow()
                .methods()
                .stream()
                .anyMatch(method ->
                        method.name().equals("publicStatic")),
                lowering);
        assertFalse(outputProgram
                .findClass("pkg/InternalCalls")
                .orElseThrow()
                .methods()
                .stream()
                .anyMatch(method ->
                                method.name().equals(
                                        "hiddenInstance")),
                lowering);
        assertFalse(outputProgram
                .findClass("pkg/InternalCalls")
                .orElseThrow()
                .methods()
                .stream()
                .anyMatch(method ->
                        method.name().equals("publicInstance")),
                lowering);
        assertEquals(
                4,
                count(lowering,
                        "\"retentionMode\": \"internalNativeOnly\""));
        assertEquals(
                4,
                count(lowering,
                        "\"javaMethodPresent\": false"));
        String packaging = Files.readString(
                workspace.resolve(
                        "reports/packaging-report.json"));
        assertTrue(packaging.contains(
                "\"rewriteStrategy\": \"internalNativeOnly\""));
        String artifactAudit = Files.readString(
                workspace.resolve(
                        "reports/artifact-audit.json"));
        assertTrue(artifactAudit.contains(
                "\"reasonCode\": \"INTERNALIZED_METHODS_REMOVED\""));
        String generatedC = generatedC(workspace);
        assertTrue(generatedC.contains("PushLocalFrame"));
        assertTrue(generatedC.contains("PopLocalFrame"));
    }

    private ResolvedConfig config(Path inputJar)
            throws IOException {
        Path analysisClasspath = temp.resolve(
                "method-internalization-analysis.jar");
        writeJar(
                analysisClasspath,
                Map.of(
                        "java/lang/Object.class",
                        objectAnalysisClass()));
        JsonObject json = JsonParser.parseString("""
                {
                  "schemaVersion": 1,
                  "jarFile": "%s",
                  "classPath": ["%s"],
                  "javaHome": null,
                  "runtimeImage": null,
                  "worldModel": "CLOSED_WORLD",
                  "outputDirectory": "out",
                  "whiteList": [
                    "pkg/InternalTargets#hiddenStatic!(Ljava/lang/String;)Ljava/lang/String;",
                    "pkg/InternalCalls#callStatic!(Ljava/lang/String;)Ljava/lang/String;",
                    "pkg/InternalCalls#hiddenInstance!(Ljava/lang/String;)Ljava/lang/String;",
                    "pkg/InternalCalls#callInstance!(Ljava/lang/String;)Ljava/lang/String;",
                    "pkg/InternalTargets#publicStatic!(Ljava/lang/String;)Ljava/lang/String;",
                    "pkg/InternalCalls#callPublicStatic!(Ljava/lang/String;)Ljava/lang/String;",
                    "pkg/InternalCalls#publicInstance!(Ljava/lang/String;)Ljava/lang/String;",
                    "pkg/InternalCalls#callPublicInstance!(Ljava/lang/String;)Ljava/lang/String;"
                  ],
                  "blackList": [],
                  "target": %s,
                  "embeddedLibraryDirectory": "native0",
                  "signaturePolicy": "fail",
                  "signing": null,
                  "intermediates": {
                    "enabled": true,
                    "includeDebugDumps": false,
                    "includePerClassIr": true,
                    "includePerClassLlvm": true,
                    "includePerClassC": true
                  },
                  "protection": {
                    "enabled": true,
                    "seed": "method-internalization-e2e",
                    "ir": {
                      "enabled": true,
                      "controlFlowFlattening": false,
                      "fakeBranches": false,
                      "basicBlockSplitting": false,
                      "constantEncryption": false,
                      "stringEncryption": false,
                      "methodInlining": false,
                      "methodSplitting": false,
                      "callIndirection": false,
                      "fieldInternalization": false,
                      "methodInternalization": true,
                      "publicMethodInternalizationAllowList": [
                        "pkg/InternalTargets#publicStatic!(Ljava/lang/String;)Ljava/lang/String;",
                        "pkg/InternalCalls#publicInstance!(Ljava/lang/String;)Ljava/lang/String;"
                      ],
                      "methodTableHiding": false,
                      "blockNameObfuscation": false
                    },
                    "llvm": {
                      "enabled": true,
                      "nameObfuscation": true,
                      "opaquePredicates": false,
                      "blockLayoutPerturbation": false,
                      "indirectCalls": false,
                      "globalLayout": false
                    },
                    "binary": {
                      "enabled": true,
                      "hideInternalSymbols": true,
                      "strip": true,
                      "removePdb": true,
                      "symbolAudit": true
                    }
                  }
                }
                 """.formatted(
                        inputJar.toString()
                                .replace("\\", "\\\\"),
                        analysisClasspath.toString()
                                .replace("\\", "\\\\"),
                        hostTargetJson()))
                .getAsJsonObject();
        return new ConfigLoader()
                .load(json, temp)
                .config()
                .orElseThrow();
    }

    private byte[] objectAnalysisClass() {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES
                        | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_SUPER,
                "java/lang/Object",
                null,
                null,
                null);
        MethodVisitor constructor = writer.visitMethod(
                ACC_PUBLIC,
                "<init>",
                "()V",
                null,
                null);
        constructor.visitCode();
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] targetsClass() {
        ClassWriter writer = writer(
                "pkg/InternalTargets");
        defaultConstructor(writer, "pkg/InternalTargets");
        MethodVisitor initializer = writer.visitMethod(
                ACC_STATIC,
                "<clinit>",
                "()V",
                null,
                null);
        initializer.visitCode();
        initializer.visitFieldInsn(
                GETSTATIC,
                "java/lang/System",
                "out",
                "Ljava/io/PrintStream;");
        initializer.visitLdcInsn("target-init");
        initializer.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/io/PrintStream",
                "println",
                "(Ljava/lang/String;)V",
                false);
        initializer.visitInsn(RETURN);
        initializer.visitMaxs(0, 0);
        initializer.visitEnd();
        MethodVisitor hidden = writer.visitMethod(
                ACC_PROTECTED | ACC_STATIC,
                "hiddenStatic",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        hidden.visitCode();
        hidden.visitVarInsn(ALOAD, 0);
        hidden.visitInsn(ICONST_1);
        hidden.visitInsn(ICONST_3);
        hidden.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/String",
                "substring",
                "(II)Ljava/lang/String;",
                false);
        hidden.visitInsn(ARETURN);
        hidden.visitMaxs(0, 0);
        hidden.visitEnd();
        MethodVisitor publicStatic = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "publicStatic",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        publicStatic.visitCode();
        publicStatic.visitVarInsn(ALOAD, 0);
        publicStatic.visitInsn(ICONST_1);
        publicStatic.visitInsn(ICONST_3);
        publicStatic.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/String",
                "substring",
                "(II)Ljava/lang/String;",
                false);
        publicStatic.visitInsn(ARETURN);
        publicStatic.visitMaxs(0, 0);
        publicStatic.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] callsClass() {
        ClassWriter writer = writer("pkg/InternalCalls");
        defaultConstructor(writer, "pkg/InternalCalls");
        MethodVisitor staticCaller = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "callStatic",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        staticCaller.visitCode();
        staticCaller.visitVarInsn(ALOAD, 0);
        staticCaller.visitMethodInsn(
                INVOKESTATIC,
                "pkg/InternalTargets",
                "hiddenStatic",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        staticCaller.visitInsn(ARETURN);
        staticCaller.visitMaxs(0, 0);
        staticCaller.visitEnd();

        MethodVisitor publicStaticCaller = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "callPublicStatic",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        publicStaticCaller.visitCode();
        publicStaticCaller.visitVarInsn(ALOAD, 0);
        publicStaticCaller.visitMethodInsn(
                INVOKESTATIC,
                "pkg/InternalTargets",
                "publicStatic",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        publicStaticCaller.visitInsn(ARETURN);
        publicStaticCaller.visitMaxs(0, 0);
        publicStaticCaller.visitEnd();

        MethodVisitor hidden = writer.visitMethod(
                ACC_PROTECTED,
                "hiddenInstance",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        hidden.visitCode();
        hidden.visitVarInsn(ALOAD, 1);
        hidden.visitInsn(ICONST_1);
        hidden.visitInsn(ICONST_3);
        hidden.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/String",
                "substring",
                "(II)Ljava/lang/String;",
                false);
        hidden.visitInsn(ARETURN);
        hidden.visitMaxs(0, 0);
        hidden.visitEnd();

        MethodVisitor publicInstance = writer.visitMethod(
                ACC_PUBLIC,
                "publicInstance",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        publicInstance.visitCode();
        publicInstance.visitVarInsn(ALOAD, 1);
        publicInstance.visitInsn(ICONST_1);
        publicInstance.visitInsn(ICONST_3);
        publicInstance.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/String",
                "substring",
                "(II)Ljava/lang/String;",
                false);
        publicInstance.visitInsn(ARETURN);
        publicInstance.visitMaxs(0, 0);
        publicInstance.visitEnd();

        MethodVisitor instanceCaller = writer.visitMethod(
                ACC_PUBLIC,
                "callInstance",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        instanceCaller.visitCode();
        instanceCaller.visitVarInsn(ALOAD, 0);
        instanceCaller.visitVarInsn(ALOAD, 1);
        instanceCaller.visitMethodInsn(
                INVOKEVIRTUAL,
                "pkg/InternalCalls",
                "hiddenInstance",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        instanceCaller.visitInsn(ARETURN);
        instanceCaller.visitMaxs(0, 0);
        instanceCaller.visitEnd();

        MethodVisitor publicInstanceCaller = writer.visitMethod(
                ACC_PUBLIC,
                "callPublicInstance",
                "(Ljava/lang/String;)Ljava/lang/String;",
                null,
                null);
        publicInstanceCaller.visitCode();
        publicInstanceCaller.visitVarInsn(ALOAD, 0);
        publicInstanceCaller.visitVarInsn(ALOAD, 1);
        publicInstanceCaller.visitMethodInsn(
                INVOKEVIRTUAL,
                "pkg/InternalCalls",
                "publicInstance",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        publicInstanceCaller.visitInsn(ARETURN);
        publicInstanceCaller.visitMaxs(0, 0);
        publicInstanceCaller.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] mainClass() {
        ClassWriter writer = writer("pkg/InternalMain");
        defaultConstructor(writer, "pkg/InternalMain");
        MethodVisitor main = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "main",
                "([Ljava/lang/String;)V",
                null,
                null);
        main.visitCode();
        main.visitFieldInsn(
                GETSTATIC,
                "java/lang/System",
                "out",
                "Ljava/io/PrintStream;");
        main.visitLdcInsn("abc");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/InternalCalls",
                "callStatic",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/io/PrintStream",
                "println",
                "(Ljava/lang/String;)V",
                false);
        main.visitFieldInsn(
                GETSTATIC,
                "java/lang/System",
                "out",
                "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "pkg/InternalCalls");
        main.visitInsn(DUP);
        main.visitMethodInsn(
                INVOKESPECIAL,
                "pkg/InternalCalls",
                "<init>",
                "()V",
                false);
        main.visitLdcInsn("xyz");
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "pkg/InternalCalls",
                "callInstance",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/io/PrintStream",
                "println",
                "(Ljava/lang/String;)V",
                false);
        main.visitFieldInsn(
                GETSTATIC,
                "java/lang/System",
                "out",
                "Ljava/io/PrintStream;");
        main.visitLdcInsn("uvw");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/InternalCalls",
                "callPublicStatic",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/io/PrintStream",
                "println",
                "(Ljava/lang/String;)V",
                false);
        main.visitFieldInsn(
                GETSTATIC,
                "java/lang/System",
                "out",
                "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, "pkg/InternalCalls");
        main.visitInsn(DUP);
        main.visitMethodInsn(
                INVOKESPECIAL,
                "pkg/InternalCalls",
                "<init>",
                "()V",
                false);
        main.visitLdcInsn("mno");
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "pkg/InternalCalls",
                "callPublicInstance",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/io/PrintStream",
                "println",
                "(Ljava/lang/String;)V",
                false);
        Label staticTryStart = new Label();
        Label staticTryEnd = new Label();
        Label staticHandler = new Label();
        Label afterStaticCatch = new Label();
        main.visitTryCatchBlock(
                staticTryStart,
                staticTryEnd,
                staticHandler,
                "java/lang/StringIndexOutOfBoundsException");
        main.visitLabel(staticTryStart);
        main.visitLdcInsn("");
        main.visitMethodInsn(
                INVOKESTATIC,
                "pkg/InternalCalls",
                "callStatic",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitInsn(POP);
        printLine(main, "static-missed");
        main.visitLabel(staticTryEnd);
        main.visitJumpInsn(GOTO, afterStaticCatch);
        main.visitLabel(staticHandler);
        main.visitVarInsn(ASTORE, 1);
        printLine(main, "static-caught");
        main.visitLabel(afterStaticCatch);

        Label instanceTryStart = new Label();
        Label instanceTryEnd = new Label();
        Label instanceHandler = new Label();
        Label afterInstanceCatch = new Label();
        main.visitTryCatchBlock(
                instanceTryStart,
                instanceTryEnd,
                instanceHandler,
                "java/lang/StringIndexOutOfBoundsException");
        main.visitLabel(instanceTryStart);
        main.visitTypeInsn(NEW, "pkg/InternalCalls");
        main.visitInsn(DUP);
        main.visitMethodInsn(
                INVOKESPECIAL,
                "pkg/InternalCalls",
                "<init>",
                "()V",
                false);
        main.visitLdcInsn("");
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "pkg/InternalCalls",
                "callInstance",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false);
        main.visitInsn(POP);
        printLine(main, "instance-missed");
        main.visitLabel(instanceTryEnd);
        main.visitJumpInsn(GOTO, afterInstanceCatch);
        main.visitLabel(instanceHandler);
        main.visitVarInsn(ASTORE, 1);
        printLine(main, "instance-caught");
        main.visitLabel(afterInstanceCatch);
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void printLine(
            MethodVisitor method,
            String value) {
        method.visitFieldInsn(
                GETSTATIC,
                "java/lang/System",
                "out",
                "Ljava/io/PrintStream;");
        method.visitLdcInsn(value);
        method.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/io/PrintStream",
                "println",
                "(Ljava/lang/String;)V",
                false);
    }

    private ClassWriter writer(String name) {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES
                        | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_SUPER,
                name,
                null,
                "java/lang/Object",
                null);
        return writer;
    }

    private void defaultConstructor(
            ClassWriter writer,
            String owner) {
        MethodVisitor constructor = writer.visitMethod(
                ACC_PUBLIC,
                "<init>",
                "()V",
                null,
                null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(
                INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                "()V",
                false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    private void writeJar(
            Path jar,
            Map<String, byte[]> entries) throws IOException {
        try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry :
                    entries.entrySet()) {
                JarEntry jarEntry =
                        new JarEntry(entry.getKey());
                jarEntry.setTime(0L);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private String generatedC(Path workspace)
            throws IOException {
        try (var paths = Files.walk(
                workspace.resolve(
                        "native/zig-workspace"))) {
            Path source = paths.filter(path ->
                            path.getFileName()
                                    .toString()
                                    .endsWith(".c"))
                    .findFirst()
                    .orElseThrow();
            return Files.readString(source);
        }
    }

    private int count(String text, String needle) {
        int result = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            result++;
            index += needle.length();
        }
        return result;
    }

    private String hostTargetJson() {
        if (realToolchainAvailable()) {
            return """
                    {
                      "windowsX64": true,
                      "windowsArm64": true,
                      "linuxX64": true,
                      "linuxArm64": true,
                      "macosX64": true,
                      "macosArm64": true
                    }""";
        }
        TargetTriple target =
                HostPlatform.detect().orElseThrow().target();
        return """
                {
                  "windowsX64": %s,
                  "windowsArm64": %s,
                  "linuxX64": %s,
                  "linuxArm64": %s,
                  "macosX64": %s,
                  "macosArm64": %s
                }""".formatted(
                target == TargetTriple.WINDOWS_X64,
                target == TargetTriple.WINDOWS_ARM64,
                target == TargetTriple.LINUX_X64,
                target == TargetTriple.LINUX_ARM64,
                target == TargetTriple.MACOS_X64,
                target == TargetTriple.MACOS_ARM64);
    }

    private boolean realToolchainAvailable() {
        Path realHome = realJ2llHome();
        return realHome != null
                && Files.isRegularFile(
                        zigExecutable(realHome));
    }

    private AutoCloseable useManagedZig()
            throws Exception {
        Path realHome = realJ2llHome();
        if (realToolchainAvailable()) {
            return useJ2llHome(realHome);
        }
        return FakeManagedZig.installAndUse(
                temp.resolve("j2ll-home"));
    }

    private Path realJ2llHome() {
        String configured =
                System.getProperty("j2ll.realHome");
        if (configured == null
                || configured.isBlank()) {
            configured =
                    System.getenv("J2LL_REAL_HOME");
        }
        return configured == null
                        || configured.isBlank()
                ? null
                : Path.of(configured)
                        .toAbsolutePath()
                        .normalize();
    }

    private Path zigExecutable(Path home) {
        return home.resolve("zig")
                .resolve(isWindows()
                        ? "zig.exe"
                        : "zig");
    }

    private AutoCloseable useJ2llHome(Path home) {
        String previous = System.getProperty(
                J2llHomeResolver.OVERRIDE_PROPERTY);
        System.setProperty(
                J2llHomeResolver.OVERRIDE_PROPERTY,
                home.toString());
        return () -> {
            if (previous == null) {
                System.clearProperty(
                        J2llHomeResolver
                                .OVERRIDE_PROPERTY);
            } else {
                System.setProperty(
                        J2llHomeResolver
                                .OVERRIDE_PROPERTY,
                        previous);
            }
        };
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }
}
