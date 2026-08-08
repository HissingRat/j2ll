package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.IADD;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V17;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
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
import xyz.melodysky.testsupport.DifferentialHarness;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.J2llHomeResolver;
import xyz.melodysky.toolchain.NativeSourceName;
import xyz.melodysky.toolchain.TargetTriple;

class ActiveUseCarrierFusionNativeRuntimeE2eTest {
    private static final String COUNTER = "pkg/ActiveUseInitCounter";
    private static final String TARGET = "pkg/FailingActiveUseTarget";
    private static final String OPS = "pkg/FusedActiveUseOps";
    private static final String MAIN = "pkg.FusedActiveUseMain";

    @TempDir
    Path temp;

    @Test
    void fusedStaticReadInitializesOnceAndRunsAcquireOnlyOnNormalContinuation()
            throws Exception {
        Path j2llHome = realJ2llHome();
        assumeTrue(
                j2llHome != null && Files.isRegularFile(zigExecutable(j2llHome)),
                "set -Dj2ll.realHome=<distribution containing zig/zig(.exe)> "
                        + "to run active-use carrier fusion native E2E");
        assertEquals("0.15.2", zigVersion(zigExecutable(j2llHome)));

        Path inputJar = writeFixtureJar(temp.resolve("active-use-fusion.jar"));
        Path workspace = temp.resolve("out/active-use-fusion");
        MainlinePipelineResult pipeline;
        try (AutoCloseable ignored = useJ2llHome(j2llHome)) {
            pipeline = new MainlinePipeline().run(
                    config(inputJar),
                    workspace,
                    xyz.melodysky.progress.BuildProgressListener.none(),
                    xyz.melodysky.analysis.world.WholeProgramAnalysisPolicy.strict(),
                    SkippedMethodApproval.allowAll());
        }

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        var differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                MAIN);
        assertEquals(0, differential.originalRun().exitCode(), differential.originalRun().stderr());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals(
                "ExceptionInInitializerError\nNoClassDefFoundError\n1\n",
                differential.outputRun().stdout());

        String llvm = Files.readString(
                workspace.resolve("native/zig-workspace/llvm")
                        .resolve(NativeSourceName.llvmFileName(OPS)));
        assertFalse(llvm.contains("localizedClassObject"), llvm);
        assertFalse(llvm.contains("call void @j2ll_rt_class_init_guard"), llvm);
        int activeCall = llvm.indexOf(" = call i32 @j2ll_h_");
        int pendingCheck = llvm.indexOf("call ptr @j2ll_rt_pending_exception");
        int acquire = llvm.indexOf("fence acquire");
        assertTrue(activeCall >= 0 && activeCall < pendingCheck, llvm);
        assertTrue(pendingCheck < acquire, llvm);
    }

    private Path writeFixtureJar(Path jar) throws Exception {
        Map<String, byte[]> entries = Map.of(
                COUNTER + ".class", counterClass(),
                TARGET + ".class", targetClass(),
                OPS + ".class", opsClass(),
                MAIN.replace('.', '/') + ".class", mainClass());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : new java.util.TreeMap<>(entries).entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0L);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }

    private byte[] counterClass() {
        ClassWriter writer = classWriter(COUNTER);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "attempts", "I", null, null)
                .visitEnd();
        defaultConstructor(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] targetClass() {
        ClassWriter writer = classWriter(TARGET);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "VALUE", "I", null, null)
                .visitEnd();
        defaultConstructor(writer);
        MethodVisitor initializer = writer.visitMethod(
                ACC_STATIC,
                "<clinit>",
                "()V",
                null,
                null);
        initializer.visitCode();
        initializer.visitFieldInsn(GETSTATIC, COUNTER, "attempts", "I");
        initializer.visitInsn(ICONST_1);
        initializer.visitInsn(IADD);
        initializer.visitFieldInsn(PUTSTATIC, COUNTER, "attempts", "I");
        initializer.visitTypeInsn(NEW, "java/lang/RuntimeException");
        initializer.visitInsn(DUP);
        initializer.visitLdcInsn("active-use-init-failure");
        initializer.visitMethodInsn(
                INVOKESPECIAL,
                "java/lang/RuntimeException",
                "<init>",
                "(Ljava/lang/String;)V",
                false);
        initializer.visitInsn(ATHROW);
        initializer.visitMaxs(0, 0);
        initializer.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] opsClass() {
        ClassWriter writer = classWriter(OPS);
        defaultConstructor(writer);
        MethodVisitor read = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "read",
                "()I",
                null,
                null);
        read.visitCode();
        read.visitFieldInsn(GETSTATIC, TARGET, "VALUE", "I");
        read.visitInsn(IRETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] mainClass() {
        String internalName = MAIN.replace('.', '/');
        ClassWriter writer = classWriter(internalName);
        defaultConstructor(writer);
        MethodVisitor main = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "main",
                "([Ljava/lang/String;)V",
                null,
                null);
        main.visitCode();
        emitFailingRead(main, 1);
        emitFailingRead(main, 1);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitFieldInsn(GETSTATIC, COUNTER, "attempts", "I");
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void emitFailingRead(MethodVisitor method, int throwableSlot) {
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        Label done = new Label();
        method.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        method.visitLabel(start);
        method.visitMethodInsn(INVOKESTATIC, OPS, "read", "()I", false);
        method.visitInsn(POP);
        method.visitLabel(end);
        method.visitJumpInsn(org.objectweb.asm.Opcodes.GOTO, done);
        method.visitLabel(handler);
        method.visitVarInsn(ASTORE, throwableSlot);
        method.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        method.visitVarInsn(ALOAD, throwableSlot);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getSimpleName", "()Ljava/lang/String;", false);
        method.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/io/PrintStream",
                "println",
                "(Ljava/lang/String;)V",
                false);
        method.visitLabel(done);
    }

    private ClassWriter classWriter(String internalName) {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        return writer;
    }

    private void defaultConstructor(ClassWriter writer) {
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
                  "whiteList": ["pkg/FusedActiveUseOps#read!()I"],
                  "blackList": [],
                  "target": %s,
                  "embeddedLibraryDirectory": "active_use_fusion_test",
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
                    "enabled": false,
                    "seed": "active-use-carrier-fusion-e2e",
                    "ir": {
                      "enabled": false,
                      "controlFlowFlattening": false,
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
                      "methodTableHiding": false,
                      "blockNameObfuscation": false
                    },
                    "llvm": {
                      "enabled": false,
                      "nameObfuscation": false,
                      "opaquePredicates": false,
                      "blockLayoutPerturbation": false,
                      "indirectCalls": false,
                      "globalLayout": false
                    },
                    "binary": {
                      "enabled": false,
                      "hideInternalSymbols": false,
                      "strip": false,
                      "removePdb": true,
                      "symbolAudit": true,
                      "retainUnwindInfo": true
                    }
                  }
                }
                """.formatted(
                inputJar.toString().replace("\\", "\\\\"),
                hostTargetJson())).getAsJsonObject();
        return new ConfigLoader().load(json, temp).config().orElseThrow();
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
        return new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
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
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }
}
