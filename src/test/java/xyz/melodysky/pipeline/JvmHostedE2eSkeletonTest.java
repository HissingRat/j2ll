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
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.testsupport.DifferentialHarness;
import xyz.melodysky.testsupport.DifferentialResult;
import xyz.melodysky.testsupport.FakeManagedZig;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.TargetTriple;

class JvmHostedE2eSkeletonTest implements Opcodes {
    @TempDir
    Path temp;

    @Test
    void differentialHarnessRunsOriginalFixtureInChildJvm() throws Exception {
        Path inputJar = temp.resolve("original-input.jar");
        writeJar(inputJar, Map.of(
                "pkg/Adder.class", adderClass("pkg/Adder"),
                "pkg/E2eMain.class", e2eMainClass("pkg/E2eMain", "pkg/Adder")));

        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                inputJar,
                "pkg.E2eMain");

        assertEquals(0, differential.originalRun().exitCode(), differential.originalRun().stderr());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals("42\n42\n", differential.originalRun().stdout());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("CHILD_JVM", differential.mode());
    }

    @Test
    void outputJarRunsNativeLoweredStaticIntAddInChildJvm() throws Exception {
        Path inputJar = temp.resolve("input.jar");
        writeJar(inputJar, Map.of(
                "pkg/Adder.class", adderClass("pkg/Adder"),
                "pkg/E2eMain.class", e2eMainClass("pkg/E2eMain", "pkg/Adder")));
        ResolvedConfig config = config(inputJar);
        Path workspace = temp.resolve("out/build_2026-06-25_00-00-02");

        MainlinePipelineResult pipeline;
        try (AutoCloseable ignored = FakeManagedZig.installAndUse(temp.resolve("j2ll-home"))) {
            pipeline = new MainlinePipeline().run(config, workspace);
        }
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.E2eMain");

        assertTrue(pipeline.successful());
        assertEquals(0, differential.originalRun().exitCode(), differential.originalRun().stderr());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals("42\n42\n", differential.originalRun().stdout());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertTrue(differential.outputArtifactPresent());
        assertEquals("CHILD_JVM", differential.mode());
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertTrue(loweringReport.contains("\"class\": \"pkg/Adder\""));
        assertTrue(loweringReport.contains("\"status\": \"lowered\""));
        assertTrue(loweringReport.contains("\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        Path hostNativeDir = workspace.resolve("native").resolve(HostPlatform.detect().orElseThrow().target().directoryName());
        assertTrue(Files.exists(hostNativeDir.resolve(HostPlatform.detect().orElseThrow().target().libraryFileName())));
        Path zigWorkspace = workspace.resolve("native/zig-workspace");
        String source = Files.readString(zigWorkspace.resolve("jni/j2lle2e.c"));
        String llvm = Files.readString(zigWorkspace.resolve("llvm/pkg_Adder.ll"));
        assertTrue(Files.readString(zigWorkspace.resolve("build.zig")).contains("b.addLibrary"));
        assertTrue(source.matches("(?s).*extern jint j2ll_f_[0-9a-f]{32}\\(.*"));
        assertFalse(source.contains("return arg0 + arg1;"));
        assertTrue(llvm.matches("(?s).*define external hidden i32 @j2ll_f_[0-9a-f]{32}\\(.*"));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(packagingReport.contains("\"generatedLoaders\""));
        assertTrue(packagingReport.contains("\"embeddedLibraries\""));
        assertTrue(packagingReport.contains("\"zigToolchain\""));
        assertTrue(packagingReport.contains("\"version\": \"0.15.2\""));
        assertTrue(packagingReport.contains("\"buildZig\""));
        assertTrue(packagingReport.contains("\"registeredNativeMethods\""));
        assertTrue(packagingReport.contains("\"exportedSymbols\""));
        assertTrue(packagingReport.contains("\"method\": \"add\""));
        assertTrue(packagingReport.contains("\"JNI_OnLoad\""));
        assertTrue(packagingReport.contains("\"j2ll_register\""));
        assertTrue(packagingReport.contains(HostPlatform.detect().orElseThrow().target().libraryFileName()));
    }

    private void writeJar(Path inputJar, Map<String, byte[]> entries) throws IOException {
        Map<String, byte[]> stableEntries = new LinkedHashMap<>(entries);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(inputJar))) {
            for (Map.Entry<String, byte[]> entry : stableEntries.entrySet()) {
                JarEntry classEntry = new JarEntry(entry.getKey());
                classEntry.setTime(0L);
                output.putNextEntry(classEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private byte[] adderClass(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor add = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "add", "(II)I", null, null);
        add.visitCode();
        add.visitVarInsn(ILOAD, 0);
        add.visitVarInsn(ILOAD, 1);
        add.visitInsn(IADD);
        add.visitInsn(IRETURN);
        add.visitMaxs(0, 0);
        add.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] e2eMainClass(String internalName, String adderInternalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor main = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitIntInsn(BIPUSH, 19);
        main.visitIntInsn(BIPUSH, 23);
        main.visitMethodInsn(INVOKESTATIC, adderInternalName, "add", "(II)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitIntInsn(BIPUSH, 40);
        main.visitInsn(ICONST_2);
        main.visitMethodInsn(INVOKESTATIC, adderInternalName, "add", "(II)I", false);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private ResolvedConfig config(Path inputJar) {
        JsonObject json = JsonParser.parseString(baseJson(inputJar)).getAsJsonObject();
        return new ConfigLoader().load(json, temp).config().orElseThrow();
    }

    private String baseJson(Path inputJar) {
        return """
                {
                  "schemaVersion": 1,
                  "jarFile": "%s",
                  "classPath": [],
                  "javaHome": null,
                  "runtimeImage": null,
                  "worldModel": "PARTIAL_WORLD",
                  "javaSupportTier": "TIER_5",
                  "fallbackMode": "nativeEmbeddedClassBlob",
                  "outputDirectory": "out",
                  "whiteList": ["pkg/Adder#add!(II)I"],
                  "blackList": [],
                  "target": %s,
                  "libraryName": "j2lle2e",
                  "embeddedLibraryDirectory": "native0",
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
                    "seed": null,
                    "intensity": "normal",
                    "ir": {
                      "enabled": true,
                      "controlFlowFlattening": { "enabled": true, "intensity": "normal" },
                      "fakeBranches": { "enabled": true, "intensity": "normal" },
                      "basicBlockSplitting": { "enabled": true, "intensity": "normal" },
                      "constantEncryption": { "enabled": true, "intensity": "normal" },
                      "stringEncryption": { "enabled": true, "intensity": "normal", "cacheStrings": false },
                      "methodInlining": { "enabled": true, "intensity": "normal" },
                      "methodSplitting": { "enabled": true, "intensity": "normal" },
                      "callIndirection": { "enabled": true, "intensity": "normal" },
                      "methodTableHiding": { "enabled": true, "intensity": "normal" }
                    },
                    "llvm": {
                      "enabled": true,
                      "nameObfuscation": { "enabled": true, "intensity": "normal" },
                      "opaquePredicates": { "enabled": true, "intensity": "normal" },
                      "blockLayoutPerturbation": { "enabled": true, "intensity": "normal" },
                      "indirectCalls": { "enabled": true, "intensity": "normal" },
                      "globalLayout": { "enabled": true, "intensity": "normal" },
                      "visibilityHardening": { "enabled": true }
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
                """.formatted(inputJar.toString().replace("\\", "\\\\"), hostTargetJson());
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
                  }""".formatted(
                target == TargetTriple.WINDOWS_X64,
                target == TargetTriple.WINDOWS_ARM64,
                target == TargetTriple.LINUX_X64,
                target == TargetTriple.LINUX_ARM64,
                target == TargetTriple.MACOS_X64,
                target == TargetTriple.MACOS_ARM64);
    }
}
