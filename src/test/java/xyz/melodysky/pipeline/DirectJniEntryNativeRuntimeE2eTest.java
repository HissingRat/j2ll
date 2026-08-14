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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
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
import xyz.melodysky.toolchain.NativeLibraryName;
import xyz.melodysky.toolchain.TargetTriple;

/** Real-JVM semantic and physical-surface regression for 6A direct JNI entries. */
class DirectJniEntryNativeRuntimeE2eTest {
    private static final List<String> DIRECT_METHODS = List.of(
            "staticVoid",
            "staticInt",
            "staticLong",
            "staticFloat",
            "staticDouble",
            "instanceInt",
            "instanceDouble");
    private static final List<String> WRAPPED_METHODS = List.of(
            "narrowBoolean",
            "narrowByte",
            "narrowChar",
            "narrowShort",
            "referenceIdentity",
            "allocateReference",
            "readField",
            "divide",
            "alwaysThrow",
            "callee",
            "caller");

    @TempDir
    Path temp;

    @Test
    void conservativeDirectEntriesRunInRealHostJvmAndExcludedShapesKeepWrappers()
            throws Exception {
        Path j2llHome = realJ2llHome();
        assumeTrue(
                j2llHome != null
                        && Files.isRegularFile(zigExecutable(j2llHome)),
                "set -Dj2ll.realHome=<distribution containing zig/zig(.exe)> "
                        + "to run the direct-JNI-entry E2E");
        assertEquals("0.15.2", runZigVersion(zigExecutable(j2llHome)));

        Path inputJar = writeFixtureJar(temp.resolve("direct-entry.jar"));
        ResolvedConfig config = config(inputJar);
        Path workspace = temp.resolve("out/direct-entry");
        MainlinePipelineResult pipeline;
        try (AutoCloseable ignored = useJ2llHome(j2llHome)) {
            pipeline = new MainlinePipeline().run(
                    config,
                    workspace,
                    xyz.melodysky.progress.BuildProgressListener.none(),
                    xyz.melodysky.analysis.world.WholeProgramAnalysisPolicy.strict(),
                    SkippedMethodApproval.allowAll());
        }

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        DifferentialResult differential = new DifferentialHarness()
                .compareOriginalToOutputJar(
                        inputJar,
                        pipeline.outputJar(),
                        "pkg.DirectEntryMain",
                        List.of("-Xcheck:jni"));
        assertEquals(0, differential.originalRun().exitCode(),
                differential.originalRun().stderr());
        assertEquals(0, differential.outputRun().exitCode(),
                differential.outputRun().stderr());
        assertEquals(
                differential.originalRun().stdout(),
                differential.outputRun().stdout());
        assertEquals(expectedOutput(), differential.outputRun().stdout());
        assertFalse(
                differential.outputRun().stderr()
                        .contains("WARNING in native method"),
                differential.outputRun().stderr());

        Map<String, String> nativeSymbols = loweringSymbols(workspace);
        assertEquals(
                DIRECT_METHODS.size() + WRAPPED_METHODS.size(),
                nativeSymbols.size());
        String generatedC = Files.readString(
                workspace.resolve("native/zig-workspace/jni/")
                        .resolve(NativeLibraryName.derive(
                                config.protection().seed()) + ".c"));
        String llvm = readLlvm(workspace.resolve(
                "native/zig-workspace/llvm"));

        for (String method : DIRECT_METHODS) {
            String symbol = nativeSymbols.get(method);
            assertTrue(symbol != null && !symbol.isBlank(), method);
            assertCDeclaration(generatedC, symbol);
            assertFalse(
                    cFunctionDefinition(generatedC, symbol),
                    () -> method + " unexpectedly retained a C wrapper\n"
                            + generatedC);
            assertTrue(
                    llvm.contains("@" + symbol + "("),
                    () -> method + " is not defined under its registered LLVM symbol\n"
                            + llvm);
        }
        for (String method : WRAPPED_METHODS) {
            String symbol = nativeSymbols.get(method);
            assertTrue(symbol != null && !symbol.isBlank(), method);
            assertTrue(
                    cFunctionDefinition(generatedC, symbol),
                    () -> method + " is outside 6A but lost its C wrapper\n"
                            + generatedC);
            assertFalse(
                    llvm.contains("@" + symbol + "("),
                    () -> method + " incorrectly became an LLVM JNI proxy\n"
                            + llvm);
        }
    }

    private Map<String, String> loweringSymbols(Path workspace)
            throws Exception {
        JsonObject report = JsonParser.parseString(Files.readString(
                        workspace.resolve("reports/lowering-report.json")))
                .getAsJsonObject();
        LinkedHashMap<String, String> symbols = new LinkedHashMap<>();
        for (var element : report.getAsJsonArray("requestedMethods")) {
            JsonObject method = element.getAsJsonObject();
            assertEquals("nativeLowered", method.get("status").getAsString());
            assertEquals(
                    "LLVM_NATIVE_PATH",
                    method.get("nativeImplementationPath").getAsString());
            String methodName = method.get("method").getAsString();
            if (DIRECT_METHODS.contains(methodName)) {
                assertEquals(
                        "llvmJniProxy",
                        method.get("nativeEntryKind").getAsString(),
                        methodName);
                assertEquals(
                        "LLVM_JNI_PROXY_PURE_SCALAR",
                        method.get("nativeEntryReasonCode").getAsString(),
                        methodName);
            } else {
                assertTrue(WRAPPED_METHODS.contains(methodName), methodName);
                assertEquals(
                        "generatedCWrapper",
                        method.get("nativeEntryKind").getAsString(),
                        methodName);
                String entryReason = method
                        .get("nativeEntryReasonCode")
                        .getAsString();
                assertTrue(
                        entryReason.startsWith("LLVM_JNI_PROXY_"),
                        methodName);
            }
            symbols.put(
                    methodName,
                    method.get("nativeSymbol").getAsString());
        }
        return Map.copyOf(symbols);
    }

    private String readLlvm(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            StringBuilder llvm = new StringBuilder();
            for (Path file : files.filter(path ->
                            path.getFileName().toString().endsWith(".ll"))
                    .sorted()
                    .toList()) {
                llvm.append(Files.readString(file)).append('\n');
            }
            return llvm.toString();
        }
    }

    private void assertCDeclaration(String source, String symbol) {
        assertTrue(
                Pattern.compile(
                                "(?m)^extern\\s+[^;\\n]+\\b"
                                        + Pattern.quote(symbol)
                                        + "\\([^;\\n]*\\);$")
                        .matcher(source)
                        .find(),
                () -> "missing C declaration for " + symbol + "\n" + source);
    }

    private boolean cFunctionDefinition(String source, String symbol) {
        return Pattern.compile(
                        "(?m)^static\\s+[^;\\n]+\\b"
                                + Pattern.quote(symbol)
                                + "\\([^;\\n]*\\)\\s*\\{$")
                .matcher(source)
                .find();
    }

    private Path writeFixtureJar(Path jar) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null,
                "a full JDK is required for the direct-JNI-entry fixture");
        Path sourceDirectory = temp.resolve("fixture-src/pkg");
        Path classesDirectory = temp.resolve("fixture-classes");
        Files.createDirectories(sourceDirectory);
        Files.createDirectories(classesDirectory);
        Path ops = sourceDirectory.resolve("DirectEntryOps.java");
        Path main = sourceDirectory.resolve("DirectEntryMain.java");
        Files.writeString(ops, opsSource(), StandardCharsets.UTF_8);
        Files.writeString(main, mainSource(), StandardCharsets.UTF_8);
        int exitCode = compiler.run(
                null,
                null,
                null,
                "--release",
                "17",
                "-d",
                classesDirectory.toString(),
                ops.toString(),
                main.toString());
        assertEquals(0, exitCode,
                "failed to compile direct-JNI-entry fixture");

        try (JarOutputStream output =
                        new JarOutputStream(Files.newOutputStream(jar));
                var paths = Files.walk(classesDirectory)) {
            for (Path classFile : paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {
                JarEntry entry = new JarEntry(
                        classesDirectory.relativize(classFile)
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
                  "worldModel": "CLOSED_WORLD",
                  "outputDirectory": "out",
                  "whiteList": [
                    "pkg/DirectEntryOps#staticVoid!()V",
                    "pkg/DirectEntryOps#staticInt!(I)I",
                    "pkg/DirectEntryOps#staticLong!(J)J",
                    "pkg/DirectEntryOps#staticFloat!(F)F",
                    "pkg/DirectEntryOps#staticDouble!(D)D",
                    "pkg/DirectEntryOps#instanceInt!(I)I",
                    "pkg/DirectEntryOps#instanceDouble!(D)D",
                    "pkg/DirectEntryOps#narrowBoolean!(Z)Z",
                    "pkg/DirectEntryOps#narrowByte!(B)B",
                    "pkg/DirectEntryOps#narrowChar!(C)C",
                    "pkg/DirectEntryOps#narrowShort!(S)S",
                    "pkg/DirectEntryOps#referenceIdentity!(Ljava/lang/Object;)Ljava/lang/Object;",
                    "pkg/DirectEntryOps#allocateReference!()Ljava/lang/Object;",
                    "pkg/DirectEntryOps#readField!()I",
                    "pkg/DirectEntryOps#divide!(II)I",
                    "pkg/DirectEntryOps#alwaysThrow!()I",
                    "pkg/DirectEntryOps#callee!(I)I",
                    "pkg/DirectEntryOps#caller!(I)I"
                  ],
                  "blackList": [],
                  "target": %s,
                  "embeddedLibraryDirectory": "direct_entry_test",
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
                    "seed": "direct-jni-entry-real-host-e2e",
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

    private String runZigVersion(Path zig) throws Exception {
        Process process = new ProcessBuilder(zig.toString(), "version").start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        return new String(
                        process.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8)
                .trim();
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

    private String expectedOutput() {
        return """
                void
                -6
                1122334455667788
                7fa12345
                8000000000000000
                16
                7ff123456789abcd
                false/true
                -128/-1/127
                0/32768/65535
                -32768/-1/32767
                true
                true
                19
                ArithmeticException
                IllegalStateException:six-a
                12
                """;
    }

    private String opsSource() {
        return """
                package pkg;

                public final class DirectEntryOps {
                    private static int state = 19;

                    public static void staticVoid() {}
                    public static int staticInt(int value) { return value + 7; }
                    public static long staticLong(long value) {
                        return value ^ 0x1020304050607080L;
                    }
                    public static float staticFloat(float value) { return value; }
                    public static double staticDouble(double value) { return value; }
                    public int instanceInt(int value) { return value + 11; }
                    public double instanceDouble(double value) { return value; }

                    public static boolean narrowBoolean(boolean value) { return value; }
                    public static byte narrowByte(byte value) { return value; }
                    public static char narrowChar(char value) { return value; }
                    public static short narrowShort(short value) { return value; }
                    public static Object referenceIdentity(Object value) { return value; }
                    public static Object allocateReference() { return new Object(); }
                    public static int readField() { return state; }
                    public static int divide(int left, int right) { return left / right; }
                    public static int alwaysThrow() {
                        throw new IllegalStateException("six-a");
                    }
                    public static int callee(int value) { return value + 3; }
                    public static int caller(int value) { return callee(value); }
                }
                """;
    }

    private String mainSource() {
        return """
                package pkg;

                public final class DirectEntryMain {
                    public static void main(String[] args) {
                        DirectEntryOps.staticVoid();
                        System.out.println("void");
                        System.out.println(DirectEntryOps.staticInt(-13));
                        System.out.println(Long.toHexString(
                                DirectEntryOps.staticLong(0x0102030405060708L)));
                        System.out.println(Integer.toHexString(Float.floatToRawIntBits(
                                DirectEntryOps.staticFloat(
                                        Float.intBitsToFloat(0x7fa12345)))));
                        System.out.println(Long.toHexString(Double.doubleToRawLongBits(
                                DirectEntryOps.staticDouble(
                                        Double.longBitsToDouble(0x8000000000000000L)))));
                        DirectEntryOps instance = new DirectEntryOps();
                        System.out.println(instance.instanceInt(5));
                        System.out.println(Long.toHexString(Double.doubleToRawLongBits(
                                instance.instanceDouble(
                                        Double.longBitsToDouble(0x7ff123456789abcdL)))));
                        System.out.println(
                                DirectEntryOps.narrowBoolean(false) + "/"
                                        + DirectEntryOps.narrowBoolean(true));
                        System.out.println(
                                DirectEntryOps.narrowByte((byte) -128) + "/"
                                        + DirectEntryOps.narrowByte((byte) -1) + "/"
                                        + DirectEntryOps.narrowByte((byte) 127));
                        System.out.println(
                                (int) DirectEntryOps.narrowChar((char) 0) + "/"
                                        + (int) DirectEntryOps.narrowChar((char) 0x8000) + "/"
                                        + (int) DirectEntryOps.narrowChar((char) 0xffff));
                        System.out.println(
                                DirectEntryOps.narrowShort((short) -32768) + "/"
                                        + DirectEntryOps.narrowShort((short) -1) + "/"
                                        + DirectEntryOps.narrowShort((short) 32767));
                        Object marker = new Object();
                        System.out.println(
                                DirectEntryOps.referenceIdentity(marker) == marker);
                        System.out.println(
                                DirectEntryOps.allocateReference() != null);
                        System.out.println(DirectEntryOps.readField());
                        try {
                            DirectEntryOps.divide(1, 0);
                            System.out.println("missing-divide-exception");
                        } catch (ArithmeticException expected) {
                            System.out.println(expected.getClass().getSimpleName());
                        }
                        try {
                            DirectEntryOps.alwaysThrow();
                            System.out.println("missing-explicit-exception");
                        } catch (IllegalStateException expected) {
                            System.out.println(expected.getClass().getSimpleName()
                                    + ":" + expected.getMessage());
                        }
                        System.out.println(DirectEntryOps.caller(9));
                    }
                }
                """;
    }
}
