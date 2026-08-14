package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.testsupport.DifferentialHarness;
import xyz.melodysky.testsupport.RealJ2llHostTestSupport;
import xyz.melodysky.toolchain.NativeLibraryName;

/** Real Zig/JVM differential coverage for 6B semantic-surface proxies. */
class SemanticJniProxyNativeRuntimeE2eTest {
    @TempDir
    Path temp;

    @Test
    void semanticSurfaceProxiesPreserveJniSemanticsUnderXcheckJni()
            throws Exception {
        Path j2llHome = RealJ2llHostTestSupport.configuredHome();
        assumeTrue(
                j2llHome != null
                        && Files.isRegularFile(
                                RealJ2llHostTestSupport.zigExecutable(j2llHome)),
                "set -Dj2ll.realHome=<distribution containing zig/zig(.exe)> "
                        + "to run the semantic-proxy E2E");
        assertEquals(
                "0.15.2",
                RealJ2llHostTestSupport.zigVersion(
                        RealJ2llHostTestSupport.zigExecutable(j2llHome)));

        SemanticJniProxyRuntimeFixture fixture =
                new SemanticJniProxyRuntimeFixture(temp);
        Path inputJar = fixture.writeJar();
        ResolvedConfig config = fixture.config(inputJar);
        Path workspace = temp.resolve("out/semantic-proxy");
        MainlinePipelineResult pipeline;
        try (AutoCloseable ignored =
                RealJ2llHostTestSupport.useHome(j2llHome)) {
            pipeline = new MainlinePipeline().run(
                    config,
                    workspace,
                    xyz.melodysky.progress.BuildProgressListener.none(),
                    xyz.melodysky.analysis.world.WholeProgramAnalysisPolicy
                            .strict(),
                    SkippedMethodApproval.allowAll());
        }

        assertTrue(pipeline.successful(), pipeline.diagnostics().toString());
        var differential = new DifferentialHarness()
                .compareOriginalToOutputJar(
                        inputJar,
                        pipeline.outputJar(),
                        "pkg.SemanticProxyMain",
                        List.of("-Xcheck:jni"));
        assertEquals(0, differential.originalRun().exitCode(),
                differential.originalRun().stderr());
        assertEquals(0, differential.outputRun().exitCode(),
                differential.outputRun().stderr());
        assertEquals(
                differential.originalRun().stdout(),
                differential.outputRun().stdout());
        assertEquals(
                SemanticJniProxyJavaSources.expectedOutput(),
                differential.outputRun().stdout());
        assertFalse(
                differential.outputRun().stderr()
                        .contains("WARNING in native method"),
                differential.outputRun().stderr());

        Map<String, EntryEvidence> entries = loweringEvidence(workspace);
        String generatedC = Files.readString(
                workspace.resolve("native/zig-workspace/jni/")
                        .resolve(NativeLibraryName.derive(
                                config.protection().seed()) + ".c"));
        String llvm = RealJ2llHostTestSupport.readLlvm(
                workspace.resolve("native/zig-workspace/llvm"));

        for (String method : SemanticJniProxyRuntimeFixture.PROXY_METHODS) {
            EntryEvidence evidence = entries.get(method);
            assertEquals("llvmJniProxy", evidence.kind(), method);
            assertTrue(
                    evidence.reason().startsWith("LLVM_JNI_PROXY_"),
                    method + ":" + evidence.reason());
            assertCDeclaration(generatedC, evidence.symbol());
            assertFalse(cFunctionDefinition(generatedC, evidence.symbol()),
                    method);
            assertTrue(llvm.contains("@" + evidence.symbol() + "("), method);
        }
        for (String method : SemanticJniProxyRuntimeFixture.WRAPPED_METHODS) {
            EntryEvidence evidence = entries.get(method);
            assertEquals("generatedCWrapper", evidence.kind(), method);
            assertTrue(cFunctionDefinition(generatedC, evidence.symbol()),
                    method);
            assertFalse(llvm.contains("@" + evidence.symbol() + "("), method);
        }
    }

    private Map<String, EntryEvidence> loweringEvidence(Path workspace)
            throws Exception {
        JsonObject report = JsonParser.parseString(Files.readString(
                        workspace.resolve("reports/lowering-report.json")))
                .getAsJsonObject();
        LinkedHashMap<String, EntryEvidence> result = new LinkedHashMap<>();
        for (var element : report.getAsJsonArray("requestedMethods")) {
            JsonObject method = element.getAsJsonObject();
            assertEquals("nativeLowered", method.get("status").getAsString());
            assertEquals(
                    "LLVM_NATIVE_PATH",
                    method.get("nativeImplementationPath").getAsString());
            result.put(
                    method.get("method").getAsString(),
                    new EntryEvidence(
                            method.get("nativeSymbol").getAsString(),
                            method.get("nativeEntryKind").getAsString(),
                            method.get("nativeEntryReasonCode").getAsString()));
        }
        assertEquals(
                SemanticJniProxyRuntimeFixture.PROXY_METHODS.size()
                        + SemanticJniProxyRuntimeFixture.WRAPPED_METHODS.size(),
                result.size());
        return Map.copyOf(result);
    }

    private void assertCDeclaration(String source, String symbol) {
        assertTrue(
                Pattern.compile(
                                "(?m)^extern\\s+[^;\\n]+\\b"
                                        + Pattern.quote(symbol)
                                        + "\\([^;\\n]*\\);$")
                        .matcher(source)
                        .find(),
                symbol);
    }

    private boolean cFunctionDefinition(String source, String symbol) {
        return Pattern.compile(
                        "(?m)^static\\s+[^;\\n]+\\b"
                                + Pattern.quote(symbol)
                                + "\\([^;\\n]*\\)\\s*\\{$")
                .matcher(source)
                .find();
    }

    private record EntryEvidence(
            String symbol,
            String kind,
            String reason) {}
}
