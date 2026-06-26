package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.config.ConfigLoader;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.JarClassFileSource;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.testsupport.FakeManagedZig;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.TargetTriple;

class MainlinePipelineIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void runsTinyStaticAddJarThroughMainlineSkeleton() throws Exception {
        Path inputJar = temp.resolve("input.jar");
        writeJar(inputJar);
        ResolvedConfig config = config(inputJar);
        Path workspace = temp.resolve("out/build_2026-06-25_00-00-00");

        MainlinePipelineResult result = runPipeline(config, workspace);

        assertTrue(result.successful());
        assertTrue(Files.exists(result.outputJar()));
        assertRewrittenAddIsNative(result.outputJar());
        assertTrue(Files.readString(workspace.resolve("reports/lowering-report.json")).contains("\"status\": \"lowered\""));
        assertTrue(Files.readString(workspace.resolve("reports/lowering-report.json")).contains("\"rewriteStrategy\": \"nativeOriginal\""));
        assertTrue(Files.readString(workspace.resolve("reports/lowering-report.json")).contains("JNI_ABI_REGISTER_NATIVES"));
        assertTrue(Files.readString(workspace.resolve("reports/packaging-report.json")).contains("\"rewrittenClasses\""));
        assertTrue(Files.readString(workspace.resolve("reports/packaging-report.json")).contains("\"zigToolchain\""));
        assertTrue(Files.readString(workspace.resolve("reports/symbol-audit.json")).contains("\"status\": \"passed\""));
        assertTrue(Files.exists(workspace.resolve("native/zig-workspace/build.zig")));
        assertTrue(Files.exists(workspace.resolve("config.resolved.json")));
        assertTrue(Files.exists(workspace.resolve("reports/frontend-skip-report.json")));
        assertTrue(Files.exists(workspace.resolve("reports/protection-report.json")));
        assertTrue(Files.readString(workspace.resolve("intermediates/runtime/runtime-metadata.json"))
                .contains("\"reflectionReachability\""));
        Path classDir = Files.walk(workspace.resolve("intermediates/classes"))
                .filter(path -> path.getFileName().toString().startsWith("Mathy__"))
                .findFirst()
                .orElseThrow();
        assertTrue(Files.exists(classDir.resolve("class-index.json")));
        assertTrue(Files.exists(classDir.resolve("method-index.json")));
        assertTrue(Files.readString(classDir.resolve("llvm/class.ll")).contains("define external hidden i32"));
        assertFalse(result.nativeRegistrationPlan().entries().isEmpty());
        assertFalse(result.nativeBuildPlan().units().isEmpty());
    }

    @Test
    void reportsHalfLoweredFallbackAndNativeEmbeddedBlobWithoutSilentSkip() throws Exception {
        Path inputJar = temp.resolve("fallback-input.jar");
        writeJar(inputJar, "pkg/JdkFallback.class", AsmFixtureBuilder.classWithUnsupportedJdkStringCall("pkg/JdkFallback"));
        ResolvedConfig config = config(inputJar, "pkg/JdkFallback#substring!(Ljava/lang/String;)Ljava/lang/String;");
        Path workspace = temp.resolve("out/build_2026-06-25_00-00-01");

        MainlinePipelineResult result = runPipeline(config, workspace);

        assertTrue(result.successful());
        String loweringReport = Files.readString(workspace.resolve("reports/lowering-report.json"));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        assertTrue(loweringReport.contains("\"status\": \"halfLowered\""));
        assertTrue(loweringReport.contains("\"fallbackMode\": \"nativeEmbeddedClassBlob\""));
        assertTrue(loweringReport.contains("JVM_HELPER_FALLBACK"));
        assertTrue(loweringReport.contains("JNI_ABI_REGISTER_NATIVES"));
        assertTrue(packagingReport.contains("\"fallbackBlobs\""));
        assertTrue(packagingReport.contains("\"storageTarget\": \"nativeEmbeddedClassBlob\""));
        assertTrue(packagingReport.contains("\"classloaderReusePolicy\": \"lazyPerClassLoaderReuse\""));
        assertFalse(packagingReport.contains(".class"));
    }

    @Test
    void reportsSelectedNonHostTargetPreflightWithoutCrossCompilingIt() throws Exception {
        Path inputJar = temp.resolve("multi-target-input.jar");
        writeJar(inputJar);
        JsonObject json = JsonParser.parseString(baseJson(inputJar, "pkg/Mathy#add!(II)I")).getAsJsonObject();
        json.add("target", JsonParser.parseString(hostPlusNonHostTargetJson()).getAsJsonObject());
        ResolvedConfig config = new ConfigLoader().load(json, temp).config().orElseThrow();
        Path workspace = temp.resolve("out/build_2026-06-25_00-00-02");

        MainlinePipelineResult result = runPipeline(config, workspace);

        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals(1, result.nativeBuildPlan().units().size());
        assertEquals(2, result.nativeBuildPlan().targetPreflights().size());
        String diagnostics = Files.readString(workspace.resolve("reports/diagnostics.json"));
        String packagingReport = Files.readString(workspace.resolve("reports/packaging-report.json"));
        String manifest = Files.readString(workspace.resolve("native/zig-workspace/j2ll-build-manifest.json"));
        TargetTriple host = HostPlatform.detect().orElseThrow().target();
        TargetTriple nonHost = nonHostTarget(host);
        assertTrue(diagnostics.contains("\"code\": \"ZIG_TARGET_PREFLIGHT\""));
        assertTrue(diagnostics.contains("\"decision\": \"skipped\""));
        assertTrue(packagingReport.contains("\"selectedTargets\""));
        assertTrue(packagingReport.contains("\"buildableTargets\""));
        assertTrue(packagingReport.contains("\"skippedTargets\""));
        assertTrue(packagingReport.contains("\"target\": \"" + nonHost.directoryName() + "\""));
        assertTrue(packagingReport.contains("\"reasonCode\": \"NON_HOST_TARGET_PREFLIGHT_ONLY\""));
        assertTrue(manifest.contains("\"target\": \"" + host.directoryName() + "\""));
        assertTrue(manifest.contains("\"target\": \"" + nonHost.directoryName() + "\""));
        assertFalse(Files.readString(workspace.resolve("native/zig-workspace/build.zig"))
                .contains("const target_" + nonHost.safeSymbol()));
    }

    private void assertRewrittenAddIsNative(Path outputJar) throws IOException {
        var parsed = new AsmClassParser()
                .parseAll(new JarClassFileSource(outputJar))
                .artifact()
                .orElseThrow()
                .program()
                .findClass("pkg/Mathy")
                .orElseThrow();
        var add = parsed.methods().stream()
                .filter(method -> method.name().equals("add"))
                .findFirst()
                .orElseThrow();
        assertTrue(add.accessFlags().isNative());
        assertFalse(add.hasCode());
    }

    private void writeJar(Path inputJar) throws IOException {
        writeJar(inputJar, "pkg/Mathy.class", AsmFixtureBuilder.classWithAddMethod("pkg/Mathy"));
    }

    private void writeJar(Path inputJar, String classEntryName, byte[] classBytes) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(inputJar))) {
            JarEntry classEntry = new JarEntry(classEntryName);
            classEntry.setTime(0L);
            output.putNextEntry(classEntry);
            output.write(classBytes);
            output.closeEntry();
            JarEntry resource = new JarEntry("data.txt");
            resource.setTime(0L);
            output.putNextEntry(resource);
            output.write("resource".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private ResolvedConfig config(Path inputJar) {
        return config(inputJar, "pkg/Mathy#add!(II)I");
    }

    private ResolvedConfig config(Path inputJar, String selector) {
        JsonObject json = JsonParser.parseString(baseJson(inputJar, selector)).getAsJsonObject();
        return new ConfigLoader().load(json, temp).config().orElseThrow();
    }

    private MainlinePipelineResult runPipeline(ResolvedConfig config, Path workspace) throws Exception {
        try (AutoCloseable ignored = FakeManagedZig.installAndUse(temp.resolve("j2ll-home"))) {
            return new MainlinePipeline().run(config, workspace);
        }
    }

    private String baseJson(Path inputJar, String selector) {
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
                  "whiteList": ["%s"],
                  "blackList": [],
                  "target": %s,
                  "libraryName": "j2lltest",
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
                """.formatted(inputJar.toString().replace("\\", "\\\\"), selector, hostTargetJson());
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

    private String hostPlusNonHostTargetJson() {
        TargetTriple host = HostPlatform.detect().orElseThrow().target();
        TargetTriple nonHost = nonHostTarget(host);
        return """
                {
                    "windowsX64": %s,
                    "windowsArm64": %s,
                    "linuxX64": %s,
                    "linuxArm64": %s,
                    "macosX64": %s,
                    "macosArm64": %s
                  }""".formatted(
                host == TargetTriple.WINDOWS_X64 || nonHost == TargetTriple.WINDOWS_X64,
                host == TargetTriple.WINDOWS_ARM64 || nonHost == TargetTriple.WINDOWS_ARM64,
                host == TargetTriple.LINUX_X64 || nonHost == TargetTriple.LINUX_X64,
                host == TargetTriple.LINUX_ARM64 || nonHost == TargetTriple.LINUX_ARM64,
                host == TargetTriple.MACOS_X64 || nonHost == TargetTriple.MACOS_X64,
                host == TargetTriple.MACOS_ARM64 || nonHost == TargetTriple.MACOS_ARM64);
    }

    private TargetTriple nonHostTarget(TargetTriple host) {
        for (TargetTriple target : TargetTriple.values()) {
            if (target != host) {
                return target;
            }
        }
        throw new IllegalStateException("no non-host target available");
    }
}
