package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.toolchain.J2llHomeResolver;
import xyz.melodysky.toolchain.NativeLibraryName;
import xyz.melodysky.toolchain.TargetTriple;
import xyz.melodysky.toolchain.symbols.NativeBinaryPrivacyInspector;
import xyz.melodysky.toolchain.symbols.NativeSymbolInspector;
import xyz.melodysky.toolchain.symbols.SymbolVisibilityPlanner;

class ProtectionCrossTargetEvidenceTest {
    private static final List<String> PASS_ROWS = List.of(
            "METHOD_INLINING",
            "METHOD_SPLITTING",
            "IR_CALL_INDIRECTION",
            "IR_CALL_INDIRECTION_BACKEND",
            "FIELD_INTERNALIZATION",
            "METHOD_TABLE_HIDING",
            "LLVM_OPAQUE_PREDICATES",
            "LLVM_BLOCK_LAYOUT_PERTURBATION",
            "LLVM_GLOBAL_LAYOUT");
    private static final List<String> SENSITIVE_IDENTITIES = List.of(
            "pkg/PassOps",
            "pkg/NativeState",
            "inlineTarget",
            "indirectIntCaller",
            "indirectPureIntCaller",
            "indirectPureLongCaller",
            "splitCandidate",
            "branchCandidate",
            "getCounter",
            "getTotal",
            "addLong",
            "distinctiveByteState",
            "distinctiveShortState",
            "distinctiveCharState",
            "distinctiveBooleanState",
            "distinctiveFloatState",
            "distinctiveDoubleState",
            "distinctiveObjectState");

    @TempDir
    Path temp;

    @Test
    void allProtectionWorkItemsBuildAndAuditAcrossSixTargets() throws Exception {
        Path j2llHome = realJ2llHome();
        assumeTrue(j2llHome != null && Files.isRegularFile(zigExecutable(j2llHome)),
                "set -Dj2ll.realHome=<distribution containing zig/zig(.exe)> "
                        + "to run the real six-target protection evidence build");
        assertEquals("0.15.2", runZigVersion(zigExecutable(j2llHome)));

        Path inputJar = ProgramProtectionNativeRuntimeE2eTest.compileFixture(temp);
        ResolvedConfig config =
                ProgramProtectionNativeRuntimeE2eTest.matrixConfig(temp, inputJar);
        Path workspace = temp.resolve("out/protection-six-target");
        MainlinePipelineResult pipeline;
        try (AutoCloseable ignored = useJ2llHome(j2llHome)) {
            pipeline = new MainlinePipeline().run(config, workspace);
        }
        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());

        JsonArray passes = protectionPasses(workspace);
        for (String pass : PASS_ROWS) {
            assertTrue(
                    hasRanPass(passes, pass),
                    pass + " => " + passEvidence(passes, pass));
        }
        assertSharedLlvmEvidence(workspace, passes);
        assertFieldAndMethodTableEvidence(workspace, config);
        assertEveryLogicalSourceFeedsEveryTarget(workspace);
        assertSixTargetArtifacts(workspace);
    }

    private void assertSharedLlvmEvidence(Path workspace, JsonArray passes) throws Exception {
        String llvm = emittedLlvm(workspace);
        for (String marker : List.of(
                "j2ll_opq_",
                "j2ll_ircit_",
                "call i32 @j2ll_nfs_get_z(",
                "call i32 @j2ll_nfs_get_b(",
                "call i32 @j2ll_nfs_get_s(",
                "call i32 @j2ll_nfs_get_c(",
                "call i32 @j2ll_nfs_get_i32(",
                "call i64 @j2ll_nfs_get_i64(",
                "call i32 @j2ll_nfs_get_f32_bits(",
                "call i64 @j2ll_nfs_get_f64_bits(",
                "call ptr @j2ll_nfs_reference_sidecar_cached(",
                "call void @j2ll_nfs_release_reference_sidecar(",
                "call ptr @j2ll_nfs_get_ref(",
                "call void @j2ll_nfs_put_ref(")) {
            assertTrue(llvm.contains(marker), marker);
        }
        for (String pass : List.of("METHOD_SPLITTING", "LLVM_GLOBAL_LAYOUT")) {
            for (String symbol : affectedSymbols(passes, pass)) {
                assertTrue(llvm.contains("@" + symbol), symbol);
            }
        }
    }

    private void assertFieldAndMethodTableEvidence(
            Path workspace,
            ResolvedConfig config) throws Exception {
        String fieldReport = Files.readString(
                workspace.resolve("reports/field-internalization-report.json"));
        assertTrue(fieldReport.contains("\"status\": \"INTERNALIZED\""));
        assertTrue(fieldReport.contains("\"removedFromOutputClass\": true"));
        assertTrue(fieldReport.contains("\"LLVM_NATIVE_PATH\""));
        for (String kind : List.of(
                "BOOLEAN", "BYTE", "SHORT", "CHAR", "INT", "LONG",
                "FLOAT", "DOUBLE", "REFERENCE")) {
            assertTrue(fieldReport.contains("\"storageKind\": \"" + kind + "\""), kind);
        }

        JsonObject methodTable = JsonParser.parseString(Files.readString(
                        workspace.resolve("reports/packaging-report.json")))
                .getAsJsonObject()
                .getAsJsonObject("methodTableHiding");
        assertEquals("RAN", methodTable.get("status").getAsString());
        assertEquals(2, methodTable.get("ownerCount").getAsInt());
        assertEquals(23, methodTable.get("bindingCount").getAsInt());
        assertEquals(
                "ownerLocalTransientStraightLine",
                methodTable.get("physicalStrategy").getAsString());
        assertFalse(methodTable.get("runtimeTokenTableEmitted").getAsBoolean());
        assertFalse(methodTable.get("runtimeFunctionTableEmitted").getAsBoolean());

        Path generatedC = workspace.resolve("native/zig-workspace/jni")
                .resolve(NativeLibraryName.derive(config.protection().seed()) + ".c");
        String source = Files.readString(generatedC);
        for (String marker : List.of(
                "JNINativeMethod methods_storage[",
                "JNINativeMethod* methods = methods_storage",
                "j2ll_native_text_zero(methods",
                "j2ll_native_field_state",
                "j2ll_nfs_get_i32",
                "j2ll_nfs_get_i64",
                "j2ll_nfs_get_f32_bits",
                "j2ll_nfs_get_f64_bits",
                "j2ll_nfs_reference_sidecar",
                "GetObjectArrayElement",
                "SetObjectArrayElement")) {
            assertTrue(source.contains(marker), marker);
        }
        assertFalse(source.contains("static const uint64_t j2ll_hmt_"));
        assertFalse(source.contains("j2ll_hidden_method_function"));
        assertFalse(source.contains("masked_token"));
        assertFalse(source.contains("join_scratch"));
        assertFalse(source.contains("NewGlobalRef"));
        for (String sensitive : SENSITIVE_IDENTITIES) {
            assertFalse(source.contains(sensitive), "generated C plaintext: " + sensitive);
        }
    }

    private void assertEveryLogicalSourceFeedsEveryTarget(Path workspace) throws Exception {
        Path zigWorkspace = workspace.resolve("native/zig-workspace");
        JsonObject manifest = JsonParser.parseString(Files.readString(
                        zigWorkspace.resolve("j2ll-build-manifest.json")))
                .getAsJsonObject();
        Set<String> expectedTargets = List.of(TargetTriple.values()).stream()
                .map(TargetTriple::directoryName)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> selectedTargets = manifest.getAsJsonArray("selectedTargets").asList().stream()
                .map(element -> element.getAsString())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(expectedTargets, selectedTargets);

        String buildZig = Files.readString(zigWorkspace.resolve("build.zig"));
        List<String> cSources = manifest.getAsJsonArray("cSources").asList().stream()
                .map(element -> element.getAsString())
                .toList();
        List<String> retainedLlvmSources = manifest.getAsJsonArray("llvmSources")
                .asList().stream()
                .map(element -> element.getAsString())
                .toList();
        JsonArray logicalLlvmSources = manifest.getAsJsonArray("llvmUnwindSources");
        assertFalse(cSources.isEmpty());
        assertFalse(logicalLlvmSources.isEmpty());
        assertEquals(
                retainedLlvmSources,
                logicalLlvmSources.asList().stream()
                        .map(element -> element.getAsJsonObject()
                                .get("retainedPath")
                                .getAsString())
                        .toList());
        for (String source : cSources) {
            assertEquals(
                    TargetTriple.values().length,
                    occurrences(buildZig, "b.path(\"" + source + "\")"),
                    source);
        }

        for (TargetTriple target : TargetTriple.values()) {
            JsonObject targetEvidence = manifest.getAsJsonArray("targets")
                    .asList().stream()
                    .map(element -> element.getAsJsonObject())
                    .filter(element -> element.get("target").getAsString()
                            .equals(target.directoryName()))
                    .findFirst()
                    .orElseThrow();
            boolean retainsGeneratedUnwind = targetEvidence
                    .get("generatedCUnwindInfoRetained")
                    .getAsBoolean();
            for (var element : logicalLlvmSources) {
                JsonObject source = element.getAsJsonObject();
                boolean omissionSafe = source.get("omissionSafe").getAsBoolean();
                String retainedPath = source.get("retainedPath").getAsString();
                String selectedPath = !retainsGeneratedUnwind && omissionSafe
                        ? source.get("omissionPath").getAsString()
                        : retainedPath;
                assertEquals(
                        1,
                        targetSourceOccurrences(buildZig, target, "llvm", selectedPath),
                        target.directoryName() + " => "
                                + source.get("owner").getAsString()
                                + " => " + selectedPath);
                if (omissionSafe) {
                    String omissionPath = source.get("omissionPath").getAsString();
                    String unselectedPath = selectedPath.equals(retainedPath)
                            ? omissionPath
                            : retainedPath;
                    assertEquals(
                            0,
                            targetSourceOccurrences(
                                    buildZig,
                                    target,
                                    "llvm",
                                    unselectedPath),
                            target.directoryName() + " must not use both LLVM unwind variants for "
                                    + source.get("owner").getAsString());
                }
            }
        }
    }

    private int targetSourceOccurrences(
            String buildZig,
            TargetTriple target,
            String sourceKind,
            String source) {
        String modulePrefix = "module_" + target.safeSymbol() + "_" + sourceKind + "_";
        String path = "b.path(\"" + source + "\")";
        return (int) buildZig.lines()
                .filter(line -> line.contains(modulePrefix) && line.contains(path))
                .count();
    }

    private void assertSixTargetArtifacts(Path workspace) throws Exception {
        NativeSymbolInspector inspector = new NativeSymbolInspector();
        for (TargetTriple target : TargetTriple.values()) {
            Path library = workspace.resolve("native").resolve(target.libraryFileName());
            assertTrue(Files.isRegularFile(library), target.directoryName());
            assertTrue(Files.size(library) > 0, target.directoryName());
            assertEquals(
                    new SymbolVisibilityPlanner().loaderExports(target).symbols().stream()
                            .map(symbol -> symbol.name())
                            .toList(),
                    inspector.exportedSymbols(target, library),
                    target.directoryName());
            byte[] binary = Files.readAllBytes(library);
            for (String sensitive : SENSITIVE_IDENTITIES) {
                assertPlaintextAbsent(binary, sensitive, target);
            }
        }
    }

    private JsonArray protectionPasses(Path workspace) throws Exception {
        return JsonParser.parseString(Files.readString(
                        workspace.resolve("reports/protection-report.json")))
                .getAsJsonObject()
                .getAsJsonArray("passes");
    }

    private String emittedLlvm(Path workspace) throws Exception {
        try (var files = Files.list(workspace.resolve("native/zig-workspace/llvm"))) {
            return files.filter(path -> path.getFileName().toString().endsWith(".ll"))
                    .sorted()
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (java.io.IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    })
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
    }

    private boolean hasRanPass(JsonArray passes, String passName) {
        return passes.asList().stream()
                .map(element -> element.getAsJsonObject())
                .anyMatch(pass -> pass.get("passName").getAsString().equals(passName)
                        && pass.get("status").getAsString().equals("RAN"));
    }

    private List<String> passEvidence(JsonArray passes, String passName) {
        return passes.asList().stream()
                .map(element -> element.getAsJsonObject())
                .filter(pass -> pass.get("passName").getAsString().equals(passName))
                .map(JsonObject::toString)
                .toList();
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

    private int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private void assertPlaintextAbsent(
            byte[] binary,
            String sensitive,
            TargetTriple target) {
        assertFalse(
                NativeBinaryPrivacyInspector.contains(
                        binary, sensitive.getBytes(StandardCharsets.UTF_8)),
                target.directoryName() + " UTF-8 plaintext: " + sensitive);
        assertFalse(
                NativeBinaryPrivacyInspector.contains(
                        binary, sensitive.getBytes(StandardCharsets.UTF_16LE)),
                target.directoryName() + " UTF-16LE plaintext: " + sensitive);
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
