package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.testsupport.DifferentialHarness;
import xyz.melodysky.testsupport.DifferentialResult;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.J2llHomeResolver;
import xyz.melodysky.toolchain.TargetTriple;

/**
 * Windows-only real managed-Zig regression for the gap left by {@code FakeManagedZig}.
 *
 * <p>The selected native methods cover a public instance reference, a private instance reference after {@code
 * setAccessible(true)}, a static reference with a {@code null} target, a primitive int, and JVM
 * identity comparisons. The identity fixture deliberately obtains the same Java object through
 * reflection and a direct field read so native code receives two independent JNI local handles.
 * It also covers inequality and null-reference comparisons.
 */
final class WindowsReflectionNativeRuntimeE2eTest {
    private static final String MAIN_CLASS = "pkg.ReflectionFieldMain";

    @TempDir
    Path temp;

    @Test
    void fieldObjectHelpersRunInWindowsChildJvmWithRealManagedZig() throws Exception {
        assumeTrue(isWindows(), "this regression requires a native Windows child JVM");
        Path j2llHome = realJ2llHome();
        assumeTrue(
                j2llHome != null && Files.isRegularFile(zigExecutable(j2llHome)),
                "set J2LL_REAL_HOME/-Dj2ll.realHome to a distribution containing zig/zig.exe, "
                        + "or set J2LL_REAL_ZIG/-Dj2ll.realZig to zig.exe");
        assumeTrue(
                "0.15.2".equals(zigVersion(zigExecutable(j2llHome))),
                "the Windows reflection E2E requires managed Zig 0.15.2");

        Path inputJar = writeFixtureJar(temp.resolve("windows-reflection-field.jar"));
        Path workspace = temp.resolve("out/windows-reflection-field");
        MainlinePipelineResult pipeline;
        try (AutoCloseable ignored = useJ2llHome(j2llHome)) {
            pipeline = new MainlinePipeline().run(config(inputJar), workspace);
        }

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        DifferentialResult differential = new DifferentialHarness().compareOriginalToOutputJar(
                inputJar,
                pipeline.outputJar(),
                MAIN_CLASS);

        assertEquals(0, differential.originalRun().exitCode(), differential.originalRun().stderr());
        assertEquals(0, differential.outputRun().exitCode(), differential.outputRun().stderr());
        assertEquals(differential.originalRun().stdout(), differential.outputRun().stdout());
        assertEquals(
                "public=true\n"
                        + "private=true\n"
                        + "static=true\n"
                        + "primitive=true\n"
                        + "sameHandles=true\n"
                        + "differentHandles=true\n"
                        + "nullHandles=true\n",
                differential.outputRun().stdout());
        assertFalse(differential.outputRun().stderr().contains("NoSuchMethodError"));

        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        assertEquals(7, countOccurrences(loweringReport, "\"nativeImplementationPath\": \"LLVM_NATIVE_PATH\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"REFLECTION_FIELD_HELPER\""));
        assertTrue(loweringReport.contains("\"reasonCode\": \"REFLECTION_ACCESSIBLE_HELPER\""));
        assertTrue(
                generatedLlvmContains(
                        workspace.resolve("native/zig-workspace/llvm"),
                        "@j2ll_rt_is_same_object"),
                "final LLVM sources must use JNI object identity rather than raw handle addresses");
    }

    private Path writeFixtureJar(Path jar) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "a full JDK is required for the Windows reflection fixture");
        Path sourceDirectory = temp.resolve("fixture-src/pkg");
        Path classesDirectory = temp.resolve("fixture-classes");
        Files.createDirectories(sourceDirectory);
        Files.createDirectories(classesDirectory);

        Path targetSource = sourceDirectory.resolve("ReflectionFieldTarget.java");
        Path opsSource = sourceDirectory.resolve("ReflectionFieldOps.java");
        Path mainSource = sourceDirectory.resolve("ReflectionFieldMain.java");
        Files.writeString(targetSource, targetSource(), StandardCharsets.UTF_8);
        Files.writeString(opsSource, opsSource(), StandardCharsets.UTF_8);
        Files.writeString(mainSource, mainSource(), StandardCharsets.UTF_8);

        int exitCode = compiler.run(
                null,
                null,
                null,
                "--release",
                "17",
                "-d",
                classesDirectory.toString(),
                targetSource.toString(),
                opsSource.toString(),
                mainSource.toString());
        assertEquals(0, exitCode, "failed to compile Windows reflection fixture");

        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
                var paths = Files.walk(classesDirectory)) {
            for (Path classFile : paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {
                JarEntry entry = new JarEntry(classesDirectory.relativize(classFile)
                        .toString()
                        .replace(File.separatorChar, '/'));
                entry.setTime(0L);
                output.putNextEntry(entry);
                output.write(Files.readAllBytes(classFile));
                output.closeEntry();
            }
        }
        return jar;
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
                    "pkg/ReflectionFieldOps#publicRoundTrip!(Lpkg/ReflectionFieldTarget;Ljava/lang/Object;)Ljava/lang/Object;",
                    "pkg/ReflectionFieldOps#privateRoundTrip!(Lpkg/ReflectionFieldTarget;Ljava/lang/Object;)Ljava/lang/Object;",
                    "pkg/ReflectionFieldOps#staticRoundTrip!(Ljava/lang/Object;)Ljava/lang/Object;",
                    "pkg/ReflectionFieldOps#primitiveRoundTrip!(Lpkg/ReflectionFieldTarget;I)I",
                    "pkg/ReflectionFieldOps#sameHandles!(Lpkg/ReflectionFieldTarget;)Z",
                    "pkg/ReflectionFieldOps#differentHandles!(Lpkg/ReflectionFieldTarget;Ljava/lang/Object;)Z",
                    "pkg/ReflectionFieldOps#nullHandles!(Lpkg/ReflectionFieldTarget;)Z"
                  ],
                  "blackList": [],
                  "target": %s,
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
                    "seed": "windows-reflection-field-e2e",
                    "ir": {
                      "enabled": true,
                      "controlFlowFlattening": true,
                      "fakeBranches": true,
                      "basicBlockSplitting": true,
                      "constantEncryption": true,
                      "stringEncryption": true,
                      "methodInlining": true,
                      "methodSplitting": true,
                      "callIndirection": true,
                      "fieldInternalization": false,
                      "methodInternalization": false,
                      "publicMethodInternalizationAllowList": [],
                      "methodTableHiding": true,
                      "blockNameObfuscation": true
                    },
                    "llvm": {
                      "enabled": true,
                      "nameObfuscation": true,
                      "opaquePredicates": true,
                      "blockLayoutPerturbation": true,
                      "indirectCalls": true,
                      "globalLayout": true
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

    private String targetSource() {
        return """
                package pkg;

                public final class ReflectionFieldTarget {
                    public Object publicValue;
                    private Object privateValue;
                    public static Object staticValue;
                    public int primitiveValue;
                }
                """;
    }

    private String opsSource() {
        return """
                package pkg;

                import java.lang.reflect.Field;

                public final class ReflectionFieldOps {
                    public static Object publicRoundTrip(ReflectionFieldTarget target, Object value)
                            throws Exception {
                        Field field = ReflectionFieldTarget.class.getDeclaredField("publicValue");
                        field.set(target, value);
                        return field.get(target);
                    }

                    public static Object privateRoundTrip(ReflectionFieldTarget target, Object value)
                            throws Exception {
                        Field field = ReflectionFieldTarget.class.getDeclaredField("privateValue");
                        field.setAccessible(true);
                        field.set(target, value);
                        return field.get(target);
                    }

                    public static Object staticRoundTrip(Object value) throws Exception {
                        Field field = ReflectionFieldTarget.class.getDeclaredField("staticValue");
                        field.set(null, value);
                        return field.get(null);
                    }

                    public static int primitiveRoundTrip(ReflectionFieldTarget target, int value)
                            throws Exception {
                        Field field = ReflectionFieldTarget.class.getDeclaredField("primitiveValue");
                        field.setInt(target, value);
                        return field.getInt(target);
                    }

                    public static boolean sameHandles(ReflectionFieldTarget target)
                            throws Exception {
                        Field field = ReflectionFieldTarget.class.getDeclaredField("publicValue");
                        Object reflected = field.get(target);
                        Object direct = target.publicValue;
                        return reflected == direct;
                    }

                    public static boolean differentHandles(
                            ReflectionFieldTarget target,
                            Object different) throws Exception {
                        Field field = ReflectionFieldTarget.class.getDeclaredField("publicValue");
                        Object reflected = field.get(target);
                        return reflected != different;
                    }

                    public static boolean nullHandles(ReflectionFieldTarget target)
                            throws Exception {
                        Field field = ReflectionFieldTarget.class.getDeclaredField("publicValue");
                        Object reflected = field.get(target);
                        Object direct = target.publicValue;
                        return reflected == direct && reflected == null;
                    }
                }
                """;
    }

    private String mainSource() {
        return """
                package pkg;

                public final class ReflectionFieldMain {
                    public static void main(String[] args) throws Exception {
                        ReflectionFieldTarget target = new ReflectionFieldTarget();
                        Object publicValue = new Object();
                        Object privateValue = new Object();
                        Object staticValue = new Object();
                        System.out.println("public="
                                + (ReflectionFieldOps.publicRoundTrip(target, publicValue) == publicValue));
                        System.out.println("private="
                                + (ReflectionFieldOps.privateRoundTrip(target, privateValue) == privateValue));
                        System.out.println("static="
                                + (ReflectionFieldOps.staticRoundTrip(staticValue) == staticValue));
                        System.out.println("primitive="
                                + (ReflectionFieldOps.primitiveRoundTrip(target, 73) == 73));
                        Object identityValue = new Object();
                        target.publicValue = identityValue;
                        System.out.println("sameHandles="
                                + ReflectionFieldOps.sameHandles(target));
                        System.out.println("differentHandles="
                                + ReflectionFieldOps.differentHandles(target, new Object()));
                        target.publicValue = null;
                        System.out.println("nullHandles="
                                + ReflectionFieldOps.nullHandles(target));
                    }
                }
                """;
    }

    private Path realJ2llHome() {
        String configuredHome = System.getProperty("j2ll.realHome");
        if (configuredHome == null || configuredHome.isBlank()) {
            configuredHome = System.getenv("J2LL_REAL_HOME");
        }
        if (configuredHome != null && !configuredHome.isBlank()) {
            return Path.of(configuredHome).toAbsolutePath().normalize();
        }

        String configuredZig = System.getProperty("j2ll.realZig");
        if (configuredZig == null || configuredZig.isBlank()) {
            configuredZig = System.getenv("J2LL_REAL_ZIG");
        }
        if (configuredZig == null || configuredZig.isBlank()) {
            return null;
        }
        Path zig = Path.of(configuredZig).toAbsolutePath().normalize();
        Path zigDirectory = zig.getParent();
        return zigDirectory == null ? null : zigDirectory.getParent();
    }

    private Path zigExecutable(Path home) {
        return home.resolve("zig/zig.exe");
    }

    private String zigVersion(Path zig) throws Exception {
        Process process = new ProcessBuilder(zig.toString(), "version").start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "zig version timed out");
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

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private boolean generatedLlvmContains(Path llvmDirectory, String needle)
            throws Exception {
        try (var paths = Files.walk(llvmDirectory)) {
            for (Path source : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".ll"))
                    .toList()) {
                if (Files.readString(source).contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
