package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.testsupport.JvmRunResult;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.J2llHomeResolver;
import xyz.melodysky.toolchain.TargetTriple;
import xyz.melodysky.toolchain.symbols.NativeSymbolInspector;
import xyz.melodysky.toolchain.symbols.SymbolVisibilityPlanner;

class ProgramProtectionNativeRuntimeE2eTest {
    private static final List<String> PROGRAM_PASS_ROWS = List.of(
            "METHOD_INLINING",
            "METHOD_SPLITTING",
            "IR_CALL_INDIRECTION",
            "IR_CALL_INDIRECTION_BACKEND",
            "LLVM_OPAQUE_PREDICATES",
            "LLVM_BLOCK_LAYOUT_PERTURBATION",
            "LLVM_GLOBAL_LAYOUT");
    @TempDir
    Path temp;

    @Test
    void programAndLlvmProtectionPassesRunInRealHostJvm() throws Exception {
        Path j2llHome = realJ2llHome();
        assumeTrue(j2llHome != null && Files.isRegularFile(zigExecutable(j2llHome)),
                "set -Dj2ll.realHome=<distribution containing zig/zig(.exe)> "
                        + "to run the real program-protection E2E");

        Path inputJar = compileFixture(temp);
        ResolvedConfig config = config(temp, inputJar);
        Path workspace = temp.resolve("out/program-protection");
        MainlinePipelineResult pipeline;
        try (AutoCloseable ignored = useJ2llHome(j2llHome)) {
            pipeline = new MainlinePipeline().run(config, workspace);
        }
        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());

        JvmRunResult original = runHarness(inputJar);
        JvmRunResult rewritten = runHarness(pipeline.outputJar());
        assertEquals(0, original.exitCode(), original.stderr());
        assertEquals(0, rewritten.exitCode(), rewritten.stderr());
        assertEquals(original.stdout(), rewritten.stdout());
        assertEquals("""
                18
                40
                33
                -1420514441
                6
                -6
                ArithmeticException
                """, rewritten.stdout());

        JsonArray passes = JsonParser.parseString(Files.readString(
                        workspace.resolve("reports/protection-report.json")))
                .getAsJsonObject()
                .getAsJsonArray("passes");
        for (String pass : PROGRAM_PASS_ROWS) {
            assertTrue(hasRanPass(passes, pass), pass);
        }

        Path llvmDirectory = workspace.resolve("native/zig-workspace/llvm");
        Path llvmFile;
        try (var files = Files.list(llvmDirectory)) {
            llvmFile = files.filter(path -> path.getFileName().toString().endsWith(".ll"))
                    .findFirst()
                    .orElseThrow();
        }
        String llvm = Files.readString(llvmFile);
        assertTrue(llvm.contains("j2ll_opq_"));
        assertTrue(llvm.contains("j2ll_ircit_"));
        for (String symbol : affectedSymbols(passes, "METHOD_SPLITTING")) {
            assertTrue(llvm.contains("@" + symbol), symbol);
        }
        for (String symbol : affectedSymbols(passes, "LLVM_GLOBAL_LAYOUT")) {
            assertTrue(llvm.contains("@" + symbol), symbol);
        }

        TargetTriple host = HostPlatform.detect().orElseThrow().target();
        Path nativeLibrary = workspace.resolve("native").resolve(host.libraryFileName());
        assertEquals(
                new SymbolVisibilityPlanner().loaderExports(host).symbols().stream()
                        .map(symbol -> symbol.name())
                        .toList(),
                new NativeSymbolInspector().exportedSymbols(host, nativeLibrary));
        String binaryText = new String(Files.readAllBytes(nativeLibrary), StandardCharsets.ISO_8859_1);
        assertFalse(binaryText.contains("pkg/PassOps"));
        assertFalse(binaryText.contains("inlineTarget"));
        assertFalse(binaryText.contains("indirectIntCaller"));
    }

    private boolean hasRanPass(JsonArray passes, String passName) {
        return passes.asList().stream()
                .map(element -> element.getAsJsonObject())
                .anyMatch(pass -> pass.get("passName").getAsString().equals(passName)
                        && pass.get("status").getAsString().equals("RAN"));
    }

    private List<String> affectedSymbols(JsonArray passes, String passName) {
        return passes.asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(pass -> pass.get("passName").getAsString().equals(passName))
                .filter(pass -> pass.get("status").getAsString().equals("RAN"))
                .flatMap(pass -> pass.getAsJsonArray("affectedSymbols").asList().stream())
                .map(element -> element.getAsString())
                .distinct()
                .toList();
    }

    static Path compileFixture(Path temp) throws Exception {
        Path sourceRoot = temp.resolve("source");
        Path classes = temp.resolve("classes");
        Path source = sourceRoot.resolve("pkg/PassOps.java");
        Path stateSource = sourceRoot.resolve("pkg/NativeState.java");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, """
                package pkg;

                public final class PassOps {
                    private PassOps() {}

                    public static int inlineTarget(int value) {
                        return value * 3 + 1;
                    }

                    public static int inlineCaller(int value) {
                        return inlineTarget(value) + 2;
                    }

                    public static int indirectIntTarget(int value) {
                        return 100 / value;
                    }

                    public static int indirectIntCaller(int value) {
                        return indirectIntTarget(value) * 2;
                    }

                    public static long indirectLongTarget(long value) {
                        return 120L / value;
                    }

                    public static long indirectLongCaller(long value) {
                        return indirectLongTarget(value) + 3L;
                    }

                    public static int splitCandidate(int left, int right) {
                        int sum = left + right;
                        int multiplied = sum * 3;
                        int mixed = multiplied ^ 0x55aa55aa;
                        return (mixed << 1) - sum;
                    }

                    public static int branchCandidate(int value) {
                        return value > 0 ? value + 1 : value - 1;
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(stateSource, """
                package pkg;

                public final class NativeState {
                    private static int counter;
                    private static long total;
                    private static byte distinctiveByteState;
                    private static short distinctiveShortState;
                    private static char distinctiveCharState;
                    private static boolean distinctiveBooleanState;
                    private static float distinctiveFloatState;
                    private static double distinctiveDoubleState;
                    private static Object distinctiveObjectState;

                    private NativeState() {}

                    public static synchronized int add(int value) {
                        counter += value;
                        return counter;
                    }

                    public static synchronized long addLong(long value) {
                        total += value;
                        return total;
                    }

                    public static int getCounter() {
                        return counter;
                    }

                    public static long getTotal() {
                        return total;
                    }

                    public static int setByte(int value) {
                        distinctiveByteState = (byte) value;
                        return distinctiveByteState;
                    }

                    public static int setShort(int value) {
                        distinctiveShortState = (short) value;
                        return distinctiveShortState;
                    }

                    public static int setChar(int value) {
                        distinctiveCharState = (char) value;
                        return distinctiveCharState;
                    }

                    public static boolean setBoolean(boolean value) {
                        distinctiveBooleanState = value;
                        return distinctiveBooleanState;
                    }

                    public static float setFloat(float value) {
                        distinctiveFloatState = value;
                        return distinctiveFloatState;
                    }

                    public static double setDouble(double value) {
                        distinctiveDoubleState = value;
                        return distinctiveDoubleState;
                    }

                    public static Object setObject(Object value) {
                        distinctiveObjectState = value;
                        return distinctiveObjectState;
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
                source.toString(),
                stateSource.toString());
        assertEquals(0, exitCode);

        Path jar = temp.resolve("program-protection.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String entryName : List.of("pkg/PassOps.class", "pkg/NativeState.class")) {
                Path classFile = classes.resolve(entryName);
                JarEntry entry = new JarEntry(entryName);
                entry.setTime(0L);
                output.putNextEntry(entry);
                output.write(Files.readAllBytes(classFile));
                output.closeEntry();
            }
        }
        return jar;
    }

    private JvmRunResult runHarness(Path jar) throws Exception {
        Path java = Path.of(System.getProperty("java.home"))
                .resolve("bin")
                .resolve(isWindows() ? "java.exe" : "java");
        Process process = new ProcessBuilder(
                        java.toString(),
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        System.getProperty("java.class.path"),
                        "xyz.melodysky.testsupport.ProgramProtectionHarness",
                        jar.toString())
                .start();
        boolean exited = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new IllegalStateException("program protection child JVM timed out");
        }
        return new JvmRunResult(
                process.exitValue(),
                normalize(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)),
                normalize(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static ResolvedConfig config(Path temp, Path inputJar) {
        return protectionConfig(
                temp,
                inputJar,
                programSelectors(),
                hostTargetJson(),
                "PARTIAL_WORLD",
                false,
                "program_protection_test",
                "program-protection-native-e2e");
    }

    static ResolvedConfig matrixConfig(Path temp, Path inputJar) {
        java.util.ArrayList<String> selectors = new java.util.ArrayList<>(programSelectors());
        selectors.addAll(List.of(
                "pkg/NativeState#add!(I)I",
                "pkg/NativeState#addLong!(J)J",
                "pkg/NativeState#getCounter!()I",
                "pkg/NativeState#getTotal!()J",
                "pkg/NativeState#setByte!(I)I",
                "pkg/NativeState#setShort!(I)I",
                "pkg/NativeState#setChar!(I)I",
                "pkg/NativeState#setBoolean!(Z)Z",
                "pkg/NativeState#setFloat!(F)F",
                "pkg/NativeState#setDouble!(D)D",
                "pkg/NativeState#setObject!(Ljava/lang/Object;)Ljava/lang/Object;"));
        return protectionConfig(
                temp,
                inputJar,
                selectors,
                allTargetsJson(),
                "CLOSED_WORLD",
                true,
                "protection_matrix_test",
                "protection-six-target-evidence");
    }

    private static List<String> programSelectors() {
        return List.of(
                "inlineTarget!(I)I",
                "inlineCaller!(I)I",
                "indirectIntTarget!(I)I",
                "indirectIntCaller!(I)I",
                "indirectLongTarget!(J)J",
                "indirectLongCaller!(J)J",
                "splitCandidate!(II)I",
                "branchCandidate!(I)I").stream()
                .map(method -> "pkg/PassOps#" + method)
                .toList();
    }

    private static ResolvedConfig protectionConfig(
            Path temp,
            Path inputJar,
            List<String> selectors,
            String targetJson,
            String worldModel,
            boolean fieldInternalization,
            String embeddedLibraryDirectory,
            String seed) {
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
                  "worldModel": "%s",
                  "outputDirectory": "out",
                  "whiteList": [%s],
                  "blackList": [],
                  "target": %s,
                  "embeddedLibraryDirectory": "%s",
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
                    "seed": "%s",
                    "ir": {
                      "enabled": true,
                      "controlFlowFlattening": false,
                      "fakeBranches": false,
                      "basicBlockSplitting": false,
                      "constantEncryption": false,
                      "stringEncryption": false,
                      "methodInlining": true,
                      "methodSplitting": true,
                      "callIndirection": true,
                      "fieldInternalization": %s,
                      "methodTableHiding": true,
                      "blockNameObfuscation": false
                    },
                    "llvm": {
                      "enabled": true,
                      "nameObfuscation": true,
                      "opaquePredicates": true,
                      "blockLayoutPerturbation": true,
                      "indirectCalls": false,
                      "globalLayout": true
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
                inputJar.toString().replace("\\", "\\\\"),
                worldModel,
                selectorJson,
                targetJson,
                embeddedLibraryDirectory,
                seed,
                fieldInternalization)).getAsJsonObject();
        return new ConfigLoader().load(json, temp).config().orElseThrow();
    }

    private static String hostTargetJson() {
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

    private static String allTargetsJson() {
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
