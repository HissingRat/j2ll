package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.JarClassFileSource;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.testsupport.DifferentialHarness;
import xyz.melodysky.testsupport.DifferentialResult;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.J2llHomeResolver;
import xyz.melodysky.toolchain.TargetTriple;
import xyz.melodysky.toolchain.localref.NativeLocalReferenceOwnership;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlanner;

class LocalReferenceNativeRuntimeE2eTest {
    @TempDir
    Path temp;

    @Test
    void duplicateOwnedHandleAcrossSuccessorParametersFailsClosed() throws Exception {
        var planning = new NativeLocalReferencePlanner().plan(
                lowerMethod(compileFixture(), "branchingCarry"));

        assertTrue(planning.plan().isEmpty(), planning.toString());
        assertEquals(
                "owned local reference is transferred to multiple reference parameters on one edge: %v34",
                planning.failureReason().orElseThrow());
    }

    @Test
    void safeLoopReferenceShapesHaveBoundedReleasePlans() throws Exception {
        Path inputJar = compileFixture();
        for (String methodName : List.of(
                "repeatedGet",
                "carriedPhi",
                "nativeNext",
                "repeatedNativeNext",
                "multiArrayFilledInNestedLoops",
                "caughtCastLoop",
                "caughtCallBeforeCarriedUse")) {
            var planning = new NativeLocalReferencePlanner().plan(
                    lowerMethod(inputJar, methodName));
            assertTrue(
                    planning.plan().isPresent(),
                    () -> localReferencePlanDebug(inputJar, methodName));
        }
    }

    @Test
    void protectedCallExceptionalBackedgeReleasesUnusedCarriedReference()
            throws Exception {
        Path inputJar = compileFixture();
        IrMethod method = lowerMethod(
                inputJar,
                "caughtCallBeforeCarriedUse");
        var plan = new NativeLocalReferencePlanner()
                .plan(method)
                .plan()
                .orElseThrow();

        boolean verified = false;
        for (var block : method.blocks()) {
            for (int index = 0;
                    index < block.instructions().size();
                    index++) {
                var instruction = block.instructions().get(index);
                if (!instruction.symbol()
                                .orElse("")
                                .equals(
                                        "java/util/List#get!(I)"
                                                + "Ljava/lang/Object;")
                        || instruction.exceptionSites().stream()
                                .noneMatch(site ->
                                        !site.handlers().isEmpty())) {
                    continue;
                }
                var releases = plan.releasesAfter(
                        block.name(),
                        index);
                assertTrue(
                        releases.exceptionalPath().stream()
                                .anyMatch(value ->
                                        !instruction.operands()
                                                        .contains(value)
                                                && plan.ownershipOf(value)
                                                        .orElseThrow()
                                                        .kind()
                                                        == NativeLocalReferenceOwnership
                                                                .Kind.DYNAMIC),
                        () -> "protected call does not release the unused "
                                + "loop-carried reference: "
                                + releases);
                assertTrue(
                        releases.normalPath().stream()
                                .noneMatch(releases.exceptionalPath()::contains),
                        () -> "exception-only cleanup leaked onto the "
                                + "normal path: "
                                + releases);
                verified = true;
            }
        }
        assertTrue(verified, () -> localReferencePlanDebug(
                inputJar,
                "caughtCallBeforeCarriedUse"));
    }

    @Test
    void boundedLoopLocalReferencesMatchJavaInRealHostJvm() throws Exception {
        Path j2llHome = realJ2llHome();
        assumeTrue(
                j2llHome != null && Files.isRegularFile(zigExecutable(j2llHome)),
                "set -Dj2ll.realHome=<distribution containing zig/zig(.exe)> "
                        + "or J2LL_REAL_HOME to run the local-reference E2E");

        Path inputJar = compileFixture();
        ResolvedConfig config = config(inputJar);
        Path workspace = temp.resolve("out/local-reference");
        MainlinePipelineResult pipeline;
        try (AutoCloseable ignored = useJ2llHome(j2llHome)) {
            pipeline = new MainlinePipeline().run(config, workspace);
        }

        assertTrue(
                pipeline.successful(),
                () -> List.of(
                                "repeatedGet",
                                "carriedPhi",
                                "nativeNext",
                                "repeatedNativeNext",
                                "caughtCastLoop",
                                "caughtCallBeforeCarriedUse")
                        .stream()
                        .map(methodName -> localReferencePlanDebug(
                                inputJar,
                                methodName))
                        .collect(java.util.stream.Collectors.joining("\n"))
                        + "\n"
                        + pipeline.diagnostics());
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                "pkg.LocalRefMain",
                List.of("-Xcheck:jni"));
        assertEquals(0, differential.originalRun().exitCode(), differential.originalRun().stderr());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertFalse(
                differential.outputRun().stderr().contains("WARNING in native method"),
                differential.outputRun().stderr());
        assertFalse(
                differential.outputRun().stderr().contains("JNI DETECTED ERROR"),
                differential.outputRun().stderr());
        assertFalse(
                differential.outputRun().stderr().contains("JNI local refs"),
                differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals("""
                250000
                250004
                250000
                200000
                150004
                """, differential.outputRun().stdout());

        String lowering = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(6, countOccurrences(lowering, "\"status\": \"nativeLowered\""), lowering);
        assertFalse(lowering.contains("\"status\": \"skipped\""), lowering);
        assertFalse(lowering.contains("UNBOUNDED_JNI_LOCAL_REFERENCE_LIFETIME"), lowering);

        String skipped = Files.readString(workspace.resolve("reports/skipped-method-report.json"));
        assertFalse(skipped.contains("\"status\": \"skipped\""), skipped);

        StringBuilder llvmBuilder = new StringBuilder();
        try (var files = Files.walk(workspace.resolve("native/zig-workspace/llvm"))) {
            for (Path source : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".ll"))
                    .sorted()
                    .toList()) {
                llvmBuilder.append(Files.readString(source)).append('\n');
            }
        }
        String llvm = llvmBuilder.toString();
        assertTrue(llvm.contains(
                "declare void @j2ll_rt_release_local_ref(ptr, ptr, i32)"), llvm);
        assertTrue(llvm.contains("call void @j2ll_rt_release_local_ref("), llvm);
        assertTrue(llvm.contains("phi i32"), llvm);

        StringBuilder generatedCBuilder = new StringBuilder();
        try (var files = Files.walk(workspace.resolve("native/zig-workspace/jni"))) {
            for (Path source : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".c"))
                    .sorted()
                    .toList()) {
                generatedCBuilder.append(Files.readString(source)).append('\n');
            }
        }
        String generatedC = generatedCBuilder.toString();
        assertTrue(generatedC.contains("void j2ll_rt_release_local_ref("), generatedC);
        assertTrue(generatedC.contains("(*env)->DeleteLocalRef(env, value);"), generatedC);
    }

    private Path compileFixture() throws Exception {
        Path sourceRoot = temp.resolve("source");
        Path classes = temp.resolve("classes");
        Path opsSource = sourceRoot.resolve("pkg/LocalRefOps.java");
        Path mainSource = sourceRoot.resolve("pkg/LocalRefMain.java");
        Files.createDirectories(opsSource.getParent());
        Files.createDirectories(classes);
        Files.writeString(opsSource, """
                package pkg;

                import java.util.List;

                public final class LocalRefOps {
                    private LocalRefOps() {}

                    public static int repeatedGet(List<?> values, int rounds) {
                        int total = 0;
                        for (int index = 0; index < rounds; index++) {
                            Object raw = values.get(index % values.size());
                            String text = (String) raw;
                            total += text.length();
                        }
                        return total;
                    }

                    public static int branchingCarry(
                            List<?> values,
                            Object seed,
                            int rounds) {
                        Object current = seed;
                        int total = 0;
                        for (int index = 0; index < rounds; index++) {
                            Object loaded = values.get(index % values.size());
                            Object selected;
                            if ((index & 1) == 0) {
                                selected = current;
                            } else {
                                selected = loaded;
                            }
                            total += ((String) selected).length();
                            current = loaded;
                        }
                        return total + ((String) current).length();
                    }

                    public static int carriedPhi(
                            List<?> values,
                            Object seed,
                            int rounds) {
                        Object current = seed;
                        int total = 0;
                        for (int index = 0; index < rounds; index++) {
                            total += ((String) current).length();
                            current = values.get(index % values.size());
                        }
                        return total + ((String) current).length();
                    }

                    public static Object nativeNext(List<?> values, int index) {
                        return values.get(index % values.size());
                    }

                    public static int repeatedNativeNext(List<?> values, int rounds) {
                        int total = 0;
                        for (int index = 0; index < rounds; index++) {
                            Object raw = nativeNext(values, index);
                            total += ((String) raw).length();
                        }
                        return total;
                    }

                    public static int[][] multiArrayFilledInNestedLoops(
                            int rows,
                            int columns) {
                        int[][] result = new int[rows][columns];
                        for (int row = 0; row < rows; row++) {
                            for (int column = 0; column < columns; column++) {
                                result[row][column] = (row + 1) * 10 + column;
                            }
                        }
                        return result;
                    }

                    public static int caughtCastLoop(List<?> values, int rounds) {
                        int total = 0;
                        for (int index = 0; index < rounds; index++) {
                            Object raw = values.get(index % values.size());
                            try {
                                total += ((String) raw).length();
                            } catch (ClassCastException ignored) {
                                total += 2;
                            }
                        }
                        return total;
                    }

                    public static int caughtCallBeforeCarriedUse(
                            List<?> values,
                            Object seed,
                            int rounds) {
                        Object current = seed;
                        int total = 0;
                        for (int index = 0; index < rounds; index++) {
                            int slot = (index & 3) == 3
                                    ? values.size()
                                    : index % values.size();
                            try {
                                Object next = values.get(slot);
                                total += ((String) current).length();
                                current = next;
                            } catch (IndexOutOfBoundsException ignored) {
                                current = values.get(0);
                                total += 2;
                            }
                        }
                        return total + ((String) current).length();
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(mainSource, """
                package pkg;

                import java.util.List;

                public final class LocalRefMain {
                    private LocalRefMain() {}

                    public static void main(String[] args) {
                        List<String> values = List.of("a", "bb", "ccc", "dddd");
                        System.out.println(LocalRefOps.repeatedGet(values, 100_000));
                        System.out.println(LocalRefOps.carriedPhi(
                                values,
                                "seed",
                                100_000));
                        System.out.println(LocalRefOps.repeatedNativeNext(values, 100_000));
                        System.out.println(LocalRefOps.caughtCastLoop(
                                List.of("a", 7, "ccc", 9L),
                                100_000));
                        System.out.println(LocalRefOps.caughtCallBeforeCarriedUse(
                                values,
                                "seed",
                                100_000));
                    }
                }
                """, StandardCharsets.UTF_8);
        int exitCode = ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "--release",
                "17",
                "-d",
                classes.toString(),
                opsSource.toString(),
                mainSource.toString());
        assertEquals(0, exitCode);

        Path jar = temp.resolve("local-reference.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String entryName : List.of(
                    "pkg/LocalRefOps.class",
                    "pkg/LocalRefMain.class")) {
                JarEntry entry = new JarEntry(entryName);
                entry.setTime(0L);
                output.putNextEntry(entry);
                output.write(Files.readAllBytes(classes.resolve(entryName)));
                output.closeEntry();
            }
        }
        return jar;
    }

    private ResolvedConfig config(Path inputJar) {
        List<String> selectors = List.of(
                "pkg/LocalRefOps#repeatedGet!(Ljava/util/List;I)I",
                "pkg/LocalRefOps#carriedPhi!(Ljava/util/List;Ljava/lang/Object;I)I",
                "pkg/LocalRefOps#nativeNext!(Ljava/util/List;I)Ljava/lang/Object;",
                "pkg/LocalRefOps#repeatedNativeNext!(Ljava/util/List;I)I",
                "pkg/LocalRefOps#caughtCastLoop!(Ljava/util/List;I)I",
                "pkg/LocalRefOps#caughtCallBeforeCarriedUse!(Ljava/util/List;Ljava/lang/Object;I)I");
        String selectorJson = selectors.stream()
                .map(selector -> "\"" + selector + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        JsonObject json = JsonParser.parseString("""
                {
                  "schemaVersion": 1,
                  "jarFile": "%s",
                  "classPath": [],
                  "javaHome": null,
                  "runtimeImage": null,
                  "worldModel": "PARTIAL_WORLD",
                  "outputDirectory": "out",
                  "whiteList": [%s],
                  "blackList": [],
                  "target": %s,
                  "embeddedLibraryDirectory": "local_reference_test",
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
                    "seed": "local-reference-native-e2e",
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
                selectorJson,
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
                  }""".formatted(
                target == TargetTriple.WINDOWS_X64,
                target == TargetTriple.WINDOWS_ARM64,
                target == TargetTriple.LINUX_X64,
                target == TargetTriple.LINUX_ARM64,
                target == TargetTriple.MACOS_X64,
                target == TargetTriple.MACOS_ARM64);
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

    private String localReferencePlanDebug(Path inputJar, String methodName) {
        IrMethod ir = lowerMethod(inputJar, methodName);
        var planning = new NativeLocalReferencePlanner().plan(ir);
        StringBuilder result = new StringBuilder()
                .append("localReferencePlan=")
                .append(planning.failureReason().orElse("success"))
                .append('\n');
        ir.blocks().forEach(block -> {
            result.append("block ")
                    .append(block.name())
                    .append(" params=")
                    .append(block.parameters())
                    .append('\n');
            for (int index = 0; index < block.instructions().size(); index++) {
                result.append("  ")
                        .append(index)
                        .append(": ")
                        .append(block.instructions().get(index))
                        .append('\n');
            }
            result.append("  terminator=")
                    .append(block.terminator())
                    .append('\n');
        });
        return result.toString();
    }

    private IrMethod lowerMethod(Path inputJar, String methodName) {
        var method = new AsmClassParser()
                .parseAll(new JarClassFileSource(inputJar))
                .artifact()
                .orElseThrow()
                .program()
                .findClass("pkg/LocalRefOps")
                .orElseThrow()
                .methods().stream()
                .filter(candidate -> candidate.name().equals(methodName))
                .findFirst()
                .orElseThrow();
        return xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer()
                .lower(new MethodCfgBuilder()
                        .build(method)
                        .artifact()
                        .orElseThrow())
                .artifact()
                .orElseThrow()
                .irMethod()
                .orElseThrow();
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
