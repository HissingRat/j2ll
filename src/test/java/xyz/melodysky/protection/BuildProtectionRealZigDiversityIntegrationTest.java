package xyz.melodysky.protection;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.backend.llvm.protection.LlvmProtectionConfig;
import xyz.melodysky.config.BinaryProtectionConfig;
import xyz.melodysky.config.IrProtectionConfig;
import xyz.melodysky.config.ProtectionConfig;
import xyz.melodysky.config.ProtectionSeedMode;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.ir.model.BusinessStringSymbolMapper;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.protection.audit.BuildArtifactFingerprint;
import xyz.melodysky.protection.audit.DualBuildFingerprintAudit;
import xyz.melodysky.protection.audit.DualBuildFingerprintResult;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.toolchain.HostPlatform;
import xyz.melodysky.toolchain.J2llHomeResolver;
import xyz.melodysky.toolchain.NativeBuildPlan;
import xyz.melodysky.toolchain.NativeBuildPlanner;
import xyz.melodysky.toolchain.NativeBuildProgressListener;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeImplementationPlanner;
import xyz.melodysky.toolchain.NativeLibraryArtifact;
import xyz.melodysky.toolchain.TargetTriple;
import xyz.melodysky.toolchain.ZigCommandResult;
import xyz.melodysky.toolchain.ZigCommandRunner;
import xyz.melodysky.toolchain.ZigNativeBuildResult;
import xyz.melodysky.toolchain.ZigNativeLibraryBuilder;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.symbols.SymbolVisibilityPlanner;

final class BuildProtectionRealZigDiversityIntegrationTest {
    private static final String OWNER = "sample/H4DiversityFixture";
    private static final String LIBRARY_NAME = "j2ll_h4_diversity";
    private static final Pattern INTERNAL_SYMBOL =
            Pattern.compile("\\bj2ll_(?:f|n)_[0-9a-f]{32}\\b");

    @TempDir
    Path temp;

    @Test
    void realZigSeparatesBuildIdentityAndReproducesExplicitIdentity()
            throws Exception {
        Path zigExecutable = realZigExecutable();
        assumeTrue(
                zigExecutable != null && Files.isRegularFile(zigExecutable),
                "set J2LL_REAL_ZIG or -Dj2ll.realZig to Zig 0.15.2");
        ZigCommandResult version = ZigCommandRunner.process().run(
                List.of(zigExecutable.toString(), "version"),
                zigExecutable.getParent(),
                Map.of());
        assumeTrue(
                version.exitCode() == 0
                        && version.stdout().trim().equals("0.15.2"),
                "the H4 real-Zig test requires Zig 0.15.2");
        HostPlatform host = HostPlatform.detect().orElse(null);
        assumeTrue(host != null, "current host is not in the supported target matrix");

        SemanticFixture semantic = semanticFixture();
        Path j2llHome = zigExecutable.toAbsolutePath().normalize()
                .getParent()
                .getParent();
        BuildOutput first;
        BuildOutput repeated;
        BuildOutput changed;
        try (AutoCloseable ignored = useJ2llHome(j2llHome)) {
            first = build(
                    temp.resolve("explicit-a-first"),
                    semantic,
                    "h4-explicit-build-root-a",
                    host.target());
            repeated = build(
                    temp.resolve("explicit-a-repeated"),
                    semantic,
                    "h4-explicit-build-root-a",
                    host.target());
            changed = build(
                    temp.resolve("explicit-b"),
                    semantic,
                    "h4-explicit-build-root-b",
                    host.target());
        }

        assertEquals(first.generatedC(), repeated.generatedC());
        assertEquals(first.llvmText(), repeated.llvmText());
        assertEquals(
                first.internalSymbolLayoutSha256(),
                repeated.internalSymbolLayoutSha256());
        DualBuildFingerprintResult reproducible =
                new DualBuildFingerprintAudit().compare(
                        ProtectionSeedMode.REPRODUCIBLE,
                        first.fingerprint(),
                        repeated.fingerprint());
        if (!reproducible.passed()
                && reproducible.reasonCode().equals(
                        DualBuildFingerprintAudit.REPRODUCIBLE_NATIVE_CHANGED)) {
            assertPeTimestampNoiseOnly(
                    first.nativeBytes(),
                    repeated.nativeBytes(),
                    host.target());
        } else {
            assertTrue(reproducible.passed(), reproducible.toString());
            assertArrayEquals(first.nativeBytes(), repeated.nativeBytes());
        }

        assertNotEquals(first.generatedC(), changed.generatedC());
        assertNotEquals(
                first.internalSymbolLayoutSha256(),
                changed.internalSymbolLayoutSha256());
        assertNotEquals(
                first.fingerprint().nativeSha256(),
                changed.fingerprint().nativeSha256());
        DualBuildFingerprintResult diverse =
                new DualBuildFingerprintAudit().compare(
                        ProtectionSeedMode.RANDOMIZED,
                        first.fingerprint(),
                        changed.fingerprint());
        assertTrue(diverse.passed(), diverse.toString());
        assertTrue(diverse.generatedCChanged());
        assertTrue(diverse.nativeChanged());

        List<String> expectedExports = new SymbolVisibilityPlanner()
                .loaderExports(host.target())
                .symbols()
                .stream()
                .map(symbol -> symbol.name())
                .toList();
        assertEquals(expectedExports, first.exports());
        assertEquals(expectedExports, repeated.exports());
        assertEquals(expectedExports, changed.exports());
        assertEquals(List.of("JNI_OnLoad"), expectedExports);
    }

    private BuildOutput build(
            Path workspace,
            SemanticFixture semantic,
            String explicitRoot,
            TargetTriple target) throws Exception {
        BuildProtectionMaterials materials = BuildProtectionMaterials.derive(
                BuildProtectionIdentity.from(protectionConfig(explicitRoot)));
        LlvmNameMangler mangler =
                LlvmNameMangler.obfuscating(materials.llvmSymbolSeed());
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(
                        semantic.decisions(),
                        materials.wrapperSeed());
        NativeTextBuildKey nativeTextKey =
                NativeTextBuildKey.fromBytes(materials.nativeTextKey());
        NativeTextBuildKey businessTextKey =
                NativeTextBuildKey.fromBytes(
                        materials.businessNativeTextKey());
        NativeTextBuildKey registrationTextKey =
                NativeTextBuildKey.fromBytes(materials.registrationKey());
        NativeImplementationPlan implementationPlan =
                new NativeImplementationPlanner(
                        mangler,
                        BusinessStringSymbolMapper.fromBytes(
                                businessTextKey.bytes()))
                        .plan(
                                registrationPlan,
                                semantic.decisions(),
                                semantic.irMethods());
        assertEquals(1, implementationPlan.implementations().size());
        assertTrue(implementationPlan.implementations().stream()
                .allMatch(implementation ->
                        implementation.path()
                                == NativeImplementationPath.LLVM_NATIVE_PATH));
        MethodTableHidingPlan methodTablePlan =
                new MethodTableHidingPlanner().plan(
                        implementationPlan.registrationPlan(),
                        false,
                        materials.methodTableSeed());
        LlvmProtectionConfig llvmProtection =
                LlvmProtectionConfig.selected(
                        materials.llvmProtectionSeed(),
                        false,
                        false,
                        true,
                        false,
                        false);
        NativeBuildPlan buildPlan = new NativeBuildPlanner().plan(
                workspace,
                LIBRARY_NAME,
                List.of(target));
        ZigNativeBuildResult result = new ZigNativeLibraryBuilder(
                        mangler,
                        llvmProtection,
                        true)
                .build(
                        workspace,
                        RuntimeLoaderPlan.create("native0"),
                        buildPlan,
                        implementationPlan,
                        semantic.irMethods(),
                        NativeBuildProgressListener.none(),
                        methodTablePlan,
                        new xyz.melodysky.toolchain.NativeLlvmCompiler(
                                new xyz.melodysky.backend.llvm.LlvmModuleLowerer(
                                        mangler,
                                        BusinessStringSymbolMapper.fromBytes(
                                                businessTextKey.bytes()),
                                        xyz.melodysky.runtime.RuntimeTokenMapper
                                                .fromBytes(nativeTextKey.bytes())),
                                new xyz.melodysky.backend.llvm.model.LlvmTextEmitter())
                                .compile(
                                        implementationPlan,
                                        semantic.irMethods(),
                                        llvmProtection),
                        nativeTextKey,
                        businessTextKey,
                        registrationTextKey)
                .orElseThrow();
        NativeLibraryArtifact artifact =
                result.artifactFor(target).orElseThrow();
        String generatedC = Files.readString(
                result.wrapperSourcePath(),
                StandardCharsets.UTF_8);
        Path llvmPath;
        try (var llvmFiles = Files.list(
                workspace.resolve("native/zig-workspace/llvm"))) {
            llvmPath = llvmFiles
                    .filter(path -> path.getFileName().toString().endsWith(".ll"))
                    .findFirst()
                    .orElseThrow();
        }
        String llvmText = Files.readString(llvmPath, StandardCharsets.UTF_8);
        byte[] nativeBytes = Files.readAllBytes(artifact.libraryPath());
        return new BuildOutput(
                generatedC,
                llvmText,
                nativeBytes,
                BuildArtifactFingerprint.of(
                        nativeBytes,
                        generatedC.getBytes(StandardCharsets.UTF_8)),
                internalSymbolLayoutSha256(generatedC, llvmText),
                artifact.exportedSymbols());
    }

    private SemanticFixture semanticFixture() {
        ParsedClass parsed = new AsmClassParser()
                .parse(new ClassFileEntry(
                        OWNER + ".class",
                        AsmFixtureBuilder.classWithSwitchStackMergeMethod(OWNER),
                        "h4-real-zig"))
                .artifact()
                .orElseThrow();
        List<MethodRewriteDecision> decisions =
                new MethodRewritePlanner().planClass(parsed).stream()
                        .filter(decision ->
                                decision.method().name().equals("selectMerged"))
                        .toList();
        LinkedHashMap<String, IrMethod> irMethods = new LinkedHashMap<>();
        for (MethodRewriteDecision decision : decisions) {
            IrMethod irMethod = new BytecodeToSsaLowerer()
                    .lower(new MethodCfgBuilder()
                            .build(decision.method())
                            .artifact()
                            .orElseThrow())
                    .artifact()
                    .orElseThrow()
                    .irMethod()
                    .orElseThrow();
            irMethods.put(decision.method().methodKey(), irMethod);
        }
        return new SemanticFixture(decisions, Map.copyOf(irMethods));
    }

    private ProtectionConfig protectionConfig(String root) {
        return new ProtectionConfig(
                true,
                root,
                ProtectionSeedMode.REPRODUCIBLE,
                new IrProtectionConfig(
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false),
                new xyz.melodysky.config.LlvmProtectionConfig(
                        false,
                        false,
                        false,
                        false,
                        false,
                        false),
                new BinaryProtectionConfig(
                        false,
                        false,
                        false,
                        false,
                        false));
    }

    private String internalSymbolLayoutSha256(
            String generatedC,
            String llvmText) throws Exception {
        StringBuilder canonical = new StringBuilder();
        Matcher cSymbols = INTERNAL_SYMBOL.matcher(generatedC);
        while (cSymbols.find()) {
            canonical.append("c:").append(cSymbols.group()).append('\n');
        }
        Matcher llvmSymbols = INTERNAL_SYMBOL.matcher(llvmText);
        while (llvmSymbols.find()) {
            canonical.append("llvm:").append(llvmSymbols.group()).append('\n');
        }
        llvmText.lines()
                .map(String::trim)
                .filter(line -> line.endsWith(":"))
                .forEach(line -> canonical.append("block:").append(line).append('\n'));
        assertFalse(canonical.isEmpty(), "no internal symbol/layout evidence");
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private void assertPeTimestampNoiseOnly(
            byte[] first,
            byte[] second,
            TargetTriple target) throws Exception {
        assertTrue(
                target.isWindows(),
                "raw native mismatch has no audited normalizer for " + target);
        PeNormalization firstNormalized = normalizePe(first);
        PeNormalization secondNormalized = normalizePe(second);
        assertArrayEquals(
                firstNormalized.bytes(),
                secondNormalized.bytes(),
                "raw PE mismatch was not confined to audited timestamp/checksum "
                        + "fields; first="
                        + firstNormalized.reason()
                        + ", second="
                        + secondNormalized.reason());
        assertEquals(
                sha256(firstNormalized.bytes()),
                sha256(secondNormalized.bytes()));
    }

    private PeNormalization normalizePe(byte[] input) {
        byte[] bytes = input.clone();
        if (bytes.length < 0x40 || bytes[0] != 'M' || bytes[1] != 'Z') {
            throw new IllegalArgumentException("native image is not PE/COFF");
        }
        int pe = readInt32(bytes, 0x3c);
        if (pe < 0 || pe + 24 > bytes.length
                || bytes[pe] != 'P'
                || bytes[pe + 1] != 'E'
                || bytes[pe + 2] != 0
                || bytes[pe + 3] != 0) {
            throw new IllegalArgumentException("invalid PE header");
        }
        int optional = pe + 24;
        int optionalSize = readUInt16(bytes, pe + 20);
        if (optional + optionalSize > bytes.length || optionalSize < 68) {
            throw new IllegalArgumentException("invalid PE optional header");
        }
        zero(bytes, pe + 8, 4);
        zero(bytes, optional + 64, 4);
        return new PeNormalization(
                bytes,
                "PE_COFF_TIMESTAMP_AND_OPTIONAL_HEADER_CHECKSUM_ZEROED");
    }

    private int readInt32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private int readUInt16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8);
    }

    private void zero(byte[] bytes, int offset, int length) {
        java.util.Arrays.fill(bytes, offset, offset + length, (byte) 0);
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Path realZigExecutable() {
        String configured = System.getProperty("j2ll.realZig");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("J2LL_REAL_ZIG");
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        String executable = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win")
                ? "zig.exe"
                : "zig";
        for (String directory :
                path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory).resolve(executable);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private AutoCloseable useJ2llHome(Path home) {
        String previous = System.getProperty(J2llHomeResolver.OVERRIDE_PROPERTY);
        System.setProperty(
                J2llHomeResolver.OVERRIDE_PROPERTY,
                home.toString());
        return () -> {
            if (previous == null) {
                System.clearProperty(J2llHomeResolver.OVERRIDE_PROPERTY);
            } else {
                System.setProperty(
                        J2llHomeResolver.OVERRIDE_PROPERTY,
                        previous);
            }
        };
    }

    private record SemanticFixture(
            List<MethodRewriteDecision> decisions,
            Map<String, IrMethod> irMethods) {
    }

    private record BuildOutput(
            String generatedC,
            String llvmText,
            byte[] nativeBytes,
            BuildArtifactFingerprint fingerprint,
            String internalSymbolLayoutSha256,
            List<String> exports) {
        private BuildOutput {
            nativeBytes = nativeBytes.clone();
            exports = List.copyOf(exports);
        }

        @Override
        public byte[] nativeBytes() {
            return nativeBytes.clone();
        }
    }

    private record PeNormalization(byte[] bytes, String reason) {
        private PeNormalization {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
