package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.JarClassFileSource;
import xyz.melodysky.testsupport.DifferentialHarness;
import xyz.melodysky.testsupport.FakeManagedZig;
import xyz.melodysky.testsupport.InterfaceMethodAsmFixtures;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.J2llHomeResolver;
import xyz.melodysky.toolchain.TargetTriple;

class InterfaceMethodNativeRuntimeE2eTest implements Opcodes {
    private static final String API = "pkg/CodeApi";
    private static final String IMPLEMENTATION = "pkg/CodeImpl";
    private static final String MAIN = "pkg.InterfaceCodeMain";

    @TempDir
    Path temp;

    @Test
    void defaultStaticAndPrivateCodeMethodsRunThroughInterfaceStubs() throws Exception {
        Path inputJar = writeFixtureJar(temp.resolve("interface-code-methods.jar"));
        Path workspace = temp.resolve("out/interface-code-methods");

        MainlinePipelineResult pipeline = runPipeline(config(inputJar), workspace);

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        var differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                MAIN);
        assertEquals(0, differential.originalRun().exitCode(), differential.originalRun().stderr());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("3\n5\n7\n", differential.outputRun().stdout());

        String loweringReport = Files.readString(
                workspace.resolve("reports/lowering-report.json"));
        assertEquals(
                3,
                countOccurrences(
                        loweringReport,
                        "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertEquals(
                3,
                countOccurrences(
                        loweringReport,
                        "\"rewriteStrategy\": \"interfaceMethodStub\""));
        assertOutputContainsOnlyStubbedInterfaceMethods(pipeline.outputJar());
    }

    private Path writeFixtureJar(Path jar) throws Exception {
        Map<String, byte[]> entries = new TreeMap<>(Map.of(
                API + ".class",
                InterfaceMethodAsmFixtures.interfaceWithDefaultStaticAndPrivate(API),
                IMPLEMENTATION + ".class",
                implementationClass(),
                MAIN.replace('.', '/') + ".class",
                mainClass()));
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
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

    private void assertOutputContainsOnlyStubbedInterfaceMethods(Path outputJar) throws Exception {
        try (JarFile jar = new JarFile(outputJar.toFile())) {
            assertEquals(
                    1,
                    jar.stream()
                            .filter(entry -> entry.getName()
                                    .matches("j2ll/generated/i_[0-9a-f]{32}\\.class"))
                            .count());
        }
        var api = new AsmClassParser()
                .parseAll(new JarClassFileSource(outputJar))
                .artifact()
                .orElseThrow()
                .program()
                .findClass(API)
                .orElseThrow();
        List<String> selectedNames = List.of(
                "defaultAnswer",
                "staticAnswer",
                "privateAnswer");
        var selected = api.methods().stream()
                .filter(method -> selectedNames.contains(method.name()))
                .toList();
        assertEquals(3, selected.size());
        assertTrue(selected.stream().allMatch(method -> method.hasCode()
                && !method.accessFlags().isNative()));
    }

    private MainlinePipelineResult runPipeline(
            ResolvedConfig config,
            Path workspace) throws Exception {
        try (AutoCloseable ignored = managedZig()) {
            return new MainlinePipeline().run(
                    config,
                    workspace,
                    xyz.melodysky.progress.BuildProgressListener.none(),
                    xyz.melodysky.analysis.world.WholeProgramAnalysisPolicy.strict(),
                    SkippedMethodApproval.allowAll());
        }
    }

    private AutoCloseable managedZig() throws Exception {
        if (!HostPlatform.detect().orElseThrow().target().isWindows()) {
            return FakeManagedZig.installAndUse(temp.resolve("j2ll-home"));
        }
        Path home = realJ2llHome();
        assumeTrue(
                home != null && Files.isRegularFile(zigExecutable(home)),
                "set J2LL_REAL_HOME/-Dj2ll.realHome to a distribution containing zig/zig.exe "
                        + "for the Windows interface-method E2E");
        return useJ2llHome(home);
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
                    "pkg/CodeApi#defaultAnswer!()I",
                    "pkg/CodeApi#staticAnswer!()I",
                    "pkg/CodeApi#privateAnswer!()I"
                  ],
                  "blackList": [],
                  "target": %s,
                  "embeddedLibraryDirectory": "interface_e2e",
                  "signaturePolicy": "fail",
                  "signing": null,
                  "intermediates": {
                    "enabled": false,
                    "includeDebugDumps": false,
                    "includePerClassIr": false,
                    "includePerClassLlvm": false,
                    "includePerClassC": false
                  },
                  "protection": {
                    "enabled": false,
                    "seed": "interface-method-native-runtime-e2e",
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

    private byte[] implementationClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_SUPER,
                IMPLEMENTATION,
                null,
                "java/lang/Object",
                new String[] {API});
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
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
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] mainClass() {
        String mainInternalName = MAIN.replace('.', '/');
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, mainInternalName, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
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

        MethodVisitor main = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "main",
                "([Ljava/lang/String;)V",
                null,
                null);
        main.visitCode();
        printNewImplementationCall(main, "defaultAnswer");
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(INVOKESTATIC, API, "staticAnswer", "()I", true);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        printNewImplementationCall(main, "callPrivate");
        main.visitInsn(RETURN);
        main.visitMaxs(0, 0);
        main.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void printNewImplementationCall(MethodVisitor main, String methodName) {
        main.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitTypeInsn(NEW, IMPLEMENTATION);
        main.visitInsn(DUP);
        main.visitMethodInsn(INVOKESPECIAL, IMPLEMENTATION, "<init>", "()V", false);
        main.visitMethodInsn(INVOKEINTERFACE, API, methodName, "()I", true);
        main.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
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
        return home.resolve("zig").resolve("zig.exe");
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

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
