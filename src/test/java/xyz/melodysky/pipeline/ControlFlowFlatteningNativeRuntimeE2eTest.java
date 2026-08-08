package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.AALOAD;
import static org.objectweb.asm.Opcodes.AASTORE;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ANEWARRAY;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.ICONST_0;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.ICONST_2;
import static org.objectweb.asm.Opcodes.ICONST_M1;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.IFLT;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V17;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.protection.audit.HashOnlyEvidence;
import xyz.melodysky.testsupport.DifferentialHarness;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.J2llHomeResolver;
import xyz.melodysky.toolchain.TargetTriple;

class ControlFlowFlatteningNativeRuntimeE2eTest {
    private static final String OPS = "pkg/CffReferenceOps";
    private static final String MAIN = "pkg.CffReferenceMain";

    @TempDir
    Path temp;

    @Test
    void scalarPrefixFlattensAroundOwnedAndExceptionBoundariesInRealHostJvm() throws Exception {
        Path j2llHome = realJ2llHome();
        assumeTrue(
                j2llHome != null && Files.isRegularFile(zigExecutable(j2llHome)),
                "set -Dj2ll.realHome=<distribution containing zig/zig(.exe)> "
                        + "to run the control-flow-flattening native E2E");
        assumeTrue(
                "0.15.2".equals(zigVersion(zigExecutable(j2llHome))),
                "the real-Zig fixture requires managed Zig 0.15.2");

        Path inputJar = writeFixtureJar(temp.resolve("cff-reference.jar"));
        ResolvedConfig config = config(inputJar);
        Path workspace = temp.resolve("out/cff-reference");
        MainlinePipelineResult pipeline;
        try (AutoCloseable ignored = useJ2llHome(j2llHome)) {
            pipeline = new MainlinePipeline().run(config, workspace);
        }

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        var differential =
                new DifferentialHarness().compareOriginalToOutputJar(inputJar, pipeline.outputJar(), MAIN);
        assertEquals(0, differential.originalRun().exitCode(), differential.originalRun().stderr());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("fallback\nzero\nvalue\ncaught\n", differential.outputRun().stdout());

        String methodKey = "pkg/CffReferenceOps#choose!(I[Ljava/lang/String;)Ljava/lang/Object;";
        String protection = Files.readString(workspace.resolve("reports/protection-report.json"));
        JsonObject cffFact = cffFact(protection, methodKey);
        assertEquals("IR", cffFact.get("layer").getAsString(), protection);
        assertTrue(cffFact.get("requested").getAsBoolean(), protection);
        assertEquals("applicable", cffFact.get("applicability").getAsString(), protection);
        assertTrue(cffFact.get("affected").getAsBoolean(), protection);
        assertEquals("RAN", cffFact.get("status").getAsString(), protection);
        assertEquals(
                "CONTROL_FLOW_FLATTENING",
                cffFact.get("reasonCode").getAsString(),
                protection);

        String llvm = emittedLlvm(workspace);
        assertTrue(llvm.contains("switch i32"), llvm);
        assertTrue(llvm.contains("call ptr @j2ll_rt_pending_exception("), llvm);
    }

    private Path writeFixtureJar(Path jar) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            writeEntry(output, "pkg/CffReferenceOps.class", opsClass());
            writeEntry(output, "pkg/CffReferenceMain.class", mainClass());
        }
        return jar;
    }

    private void writeEntry(JarOutputStream output, String name, byte[] bytes) throws Exception {
        JarEntry entry = new JarEntry(name);
        entry.setTime(0L);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    private byte[] opsClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, OPS, null, "java/lang/Object", null);
        writer.visitField(ACC_STATIC, "fallback", "Ljava/lang/Object;", null, null).visitEnd();

        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor initializer = writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        initializer.visitCode();
        initializer.visitLdcInsn("fallback");
        initializer.visitFieldInsn(PUTSTATIC, OPS, "fallback", "Ljava/lang/Object;");
        initializer.visitInsn(RETURN);
        initializer.visitMaxs(0, 0);
        initializer.visitEnd();

        MethodVisitor choose = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "choose",
                "(I[Ljava/lang/String;)Ljava/lang/Object;",
                null,
                null);
        Label fallback = new Label();
        Label indexed = new Label();
        choose.visitCode();
        choose.visitVarInsn(ILOAD, 0);
        choose.visitJumpInsn(IFLT, fallback);
        choose.visitVarInsn(ILOAD, 0);
        choose.visitJumpInsn(IFNE, indexed);
        choose.visitVarInsn(ALOAD, 1);
        choose.visitInsn(ICONST_0);
        choose.visitInsn(AALOAD);
        choose.visitInsn(ARETURN);
        choose.visitLabel(indexed);
        choose.visitVarInsn(ALOAD, 1);
        choose.visitVarInsn(ILOAD, 0);
        choose.visitInsn(AALOAD);
        choose.visitInsn(ARETURN);
        choose.visitLabel(fallback);
        choose.visitFieldInsn(GETSTATIC, OPS, "fallback", "Ljava/lang/Object;");
        choose.visitInsn(ARETURN);
        choose.visitMaxs(0, 0);
        choose.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] mainClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/CffReferenceMain", null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor main = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "main",
                "([Ljava/lang/String;)V",
                null,
                null);
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchHandler = new Label();
        Label afterCatch = new Label();
        main.visitTryCatchBlock(
                tryStart,
                tryEnd,
                catchHandler,
                "java/lang/ArrayIndexOutOfBoundsException");
        main.visitCode();
        emitChooseAndPrint(main, -1);
        emitChooseAndPrint(main, 0, "zero");
        emitChooseAndPrint(main, 1, "unused", "value");
        main.visitLabel(tryStart);
        emitChooseAndPrint(main, 2, "only");
        main.visitLabel(tryEnd);
        main.visitJumpInsn(GOTO, afterCatch);
        main.visitLabel(catchHandler);
        main.visitVarInsn(ASTORE, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitLdcInsn("caught");
        main.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/io/PrintStream",
                "println",
                "(Ljava/lang/String;)V",
                false);
        main.visitLabel(afterCatch);
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void emitChooseAndPrint(
            MethodVisitor method,
            int selector,
            String... values) {
        method.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        pushSmallInt(method, selector);
        pushSmallInt(method, values.length);
        method.visitTypeInsn(ANEWARRAY, "java/lang/String");
        for (int index = 0; index < values.length; index++) {
            method.visitInsn(DUP);
            pushSmallInt(method, index);
            method.visitLdcInsn(values[index]);
            method.visitInsn(AASTORE);
        }
        method.visitMethodInsn(
                INVOKESTATIC,
                OPS,
                "choose",
                "(I[Ljava/lang/String;)Ljava/lang/Object;",
                false);
        method.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/io/PrintStream",
                "println",
                "(Ljava/lang/Object;)V",
                false);
    }

    private void pushSmallInt(MethodVisitor method, int value) {
        int opcode = switch (value) {
            case -1 -> ICONST_M1;
            case 0 -> ICONST_0;
            case 1 -> ICONST_1;
            case 2 -> ICONST_2;
            default -> throw new IllegalArgumentException("fixture integer is outside the small range: " + value);
        };
        method.visitInsn(opcode);
    }

    private JsonObject cffFact(String protection, String methodKey) {
        JsonObject root = JsonParser.parseString(protection).getAsJsonObject();
        JsonObject coverage = root.getAsJsonObject("coverage");
        assertTrue(coverage != null, protection);
        JsonArray facts = coverage.getAsJsonArray("facts");
        assertTrue(facts != null, protection);
        String subjectIdentityHash = HashOnlyEvidence.sha256(
                "protection-report-method-subject",
                methodKey);
        List<JsonObject> matches = java.util.stream.StreamSupport.stream(
                        facts.spliterator(),
                        false)
                .map(JsonElement::getAsJsonObject)
                .filter(fact -> "IR".equals(fact.get("layer").getAsString()))
                .filter(fact -> "CONTROL_FLOW_FLATTENING".equals(
                        fact.get("passName").getAsString()))
                .filter(fact -> subjectIdentityHash.equals(
                        fact.get("subjectIdentityHash").getAsString()))
                .toList();
        assertEquals(1, matches.size(), protection);
        return matches.get(0);
    }

    private ResolvedConfig config(Path inputJar) {
        JsonObject json = JsonParser.parseString("""
                {
                  "schemaVersion": 1,
                  "jarFile": "%s",
                  "classPath": [],
                  "javaHome": null,
                  "runtimeImage": null,
                  "worldModel": "PARTIAL_WORLD",
                  "outputDirectory": "out",
                  "whiteList": [
                    "pkg/CffReferenceOps#choose!(I[Ljava/lang/String;)Ljava/lang/Object;"
                  ],
                  "blackList": [],
                  "target": %s,
                  "embeddedLibraryDirectory": "cff_reference_test",
                  "signaturePolicy": "fail",
                  "signing": null,
                  "intermediates": {
                    "enabled": true,
                    "includeDebugDumps": true,
                    "includePerClassIr": true,
                    "includePerClassLlvm": true,
                    "includePerClassC": true
                  },
                  "protection": {
                    "enabled": true,
                    "seed": "cff-reference-native-e2e",
                    "ir": {
                      "enabled": true,
                      "controlFlowFlattening": true,
                      "fakeBranches": false,
                      "basicBlockSplitting": false,
                      "constantEncryption": false,
                      "stringEncryption": false,
                      "methodInlining": false,
                      "methodSplitting": false,
                      "callIndirection": false,
                      "fieldInternalization": false,
                      "methodInternalization": false,
                      "publicMethodInternalizationAllowList": [],
                      "methodTableHiding": true,
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
                      "symbolAudit": true,
                      "retainUnwindInfo": false
                    }
                  }
                }
                """.formatted(
                inputJar.toString().replace("\\", "\\\\"),
                hostTargetJson())).getAsJsonObject();
        return new ConfigLoader().load(json, temp).config().orElseThrow();
    }

    private String emittedLlvm(Path workspace) throws Exception {
        try (var files = Files.list(workspace.resolve("native/zig-workspace/llvm"))) {
            return files.filter(path -> path.getFileName().toString().endsWith(".ll"))
                    .sorted()
                    .map(this::read)
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private String hostTargetJson() {
        TargetTriple target = HostPlatform.detect().orElseThrow().target();
        return """
                {
                  "windowsX64": %s,
                  "windowsArm64": %s,
                  "linuxX64": %s,
                  "linuxArm64": %s,
                  "macosX64": %s,
                  "macosArm64": %s
                }
                """.formatted(
                target == TargetTriple.WINDOWS_X64,
                target == TargetTriple.WINDOWS_ARM64,
                target == TargetTriple.LINUX_X64,
                target == TargetTriple.LINUX_ARM64,
                target == TargetTriple.MACOS_X64,
                target == TargetTriple.MACOS_ARM64);
    }

    private Path realJ2llHome() {
        String configured = System.getProperty("j2ll.realHome");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("J2LL_REAL_HOME");
        }
        return configured == null || configured.isBlank()
                ? null
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private Path zigExecutable(Path home) {
        return home.resolve("zig").resolve(isWindows() ? "zig.exe" : "zig");
    }

    private String zigVersion(Path zig) throws Exception {
        Process process = new ProcessBuilder(zig.toString(), "version").start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    }

    private AutoCloseable useJ2llHome(Path home) {
        String previous = System.getProperty(J2llHomeResolver.OVERRIDE_PROPERTY);
        System.setProperty(J2llHomeResolver.OVERRIDE_PROPERTY, home.toString());
        return () -> {
            if (previous == null) {
                System.clearProperty(J2llHomeResolver.OVERRIDE_PROPERTY);
            } else {
                System.setProperty(J2llHomeResolver.OVERRIDE_PROPERTY, previous);
            }
        };
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
