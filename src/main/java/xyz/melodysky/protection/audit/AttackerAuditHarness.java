package xyz.melodysky.protection.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import xyz.melodysky.toolchain.nativetext.GeneratedNativeHardeningAudit;
import xyz.melodysky.toolchain.nativetext.GeneratedNativeHardeningAuditResult;
import xyz.melodysky.toolchain.symbols.NativeSymbolInspector;
import xyz.melodysky.toolchain.symbols.SymbolAudit;
import xyz.melodysky.toolchain.symbols.SymbolVisibilityPlanner;

/**
 * Lightweight, deterministic attacker-view scan of final native and generated-C
 * artifacts. It reports evidence and metrics but does not decide pipeline
 * release policy.
 */
public final class AttackerAuditHarness {
    private final NativeExportReader exportReader;
    private final GeneratedNativeHardeningAudit generatedCAudit;
    private final NativeSurfaceScanner surfaceScanner;

    public AttackerAuditHarness() {
        this(
                new NativeSymbolInspector()::exportedSymbols,
                new GeneratedNativeHardeningAudit(),
                new NativeSurfaceScanner());
    }

    AttackerAuditHarness(NativeExportReader exportReader) {
        this(
                exportReader,
                new GeneratedNativeHardeningAudit(),
                new NativeSurfaceScanner());
    }

    private AttackerAuditHarness(
            NativeExportReader exportReader,
            GeneratedNativeHardeningAudit generatedCAudit,
            NativeSurfaceScanner surfaceScanner) {
        this.exportReader = java.util.Objects.requireNonNull(
                exportReader,
                "exportReader");
        this.generatedCAudit = java.util.Objects.requireNonNull(
                generatedCAudit,
                "generatedCAudit");
        this.surfaceScanner = java.util.Objects.requireNonNull(
                surfaceScanner,
                "surfaceScanner");
    }

    public AttackerAuditMetrics audit(AttackerAuditRequest request)
            throws IOException {
        byte[] nativeBytes = Files.readAllBytes(request.nativeLibrary());
        byte[] generatedCBytes = Files.readAllBytes(request.generatedC());
        String generatedC =
                new String(generatedCBytes, StandardCharsets.UTF_8);

        var sourceAudit = generatedCAudit.audit(generatedC);
        NativeSurfaceMetrics surface = surfaceScanner.scan(
                nativeBytes,
                generatedC,
                request.sensitivePlaintexts());
        int fallbackOccurrences = surface.fallbackCarrierOccurrences()
                + findingCount(
                        sourceAudit,
                        GeneratedNativeHardeningAudit.FALLBACK_BYTECODE_CARRIER);
        int classMagicOccurrences = surface.classMagicOccurrences()
                + findingCount(
                        sourceAudit,
                        GeneratedNativeHardeningAudit.CLASSFILE_MAGIC_CARRIER);
        int legacyMetadataOccurrences = surface.legacyGlobalMetadataOccurrences()
                + findingCount(
                        sourceAudit,
                        GeneratedNativeHardeningAudit
                                .LEGACY_GLOBAL_METADATA_DIRECTORY);
        int legacyDecodeOccurrences = surface.legacyDecodeAllOccurrences()
                + findingCount(
                        sourceAudit,
                        GeneratedNativeHardeningAudit.LEGACY_DECODE_ALL_ROUTINE);

        List<String> exports = exportReader.read(
                request.target(),
                request.nativeLibrary());
        var symbolAudit = new SymbolAudit().audit(
                new SymbolVisibilityPlanner().loaderExports(request.target()),
                exports);
        boolean sensitivePlaintextAbsent = surface.sensitivePlaintextMetrics()
                .stream()
                .allMatch(metric -> metric.totalOccurrences() == 0);
        boolean passed = sourceAudit.passed()
                && fallbackOccurrences == 0
                && classMagicOccurrences == 0
                && legacyMetadataOccurrences == 0
                && legacyDecodeOccurrences == 0
                && sensitivePlaintextAbsent
                && symbolAudit.passed();

        return new AttackerAuditMetrics(
                request.target().directoryName(),
                sha256(nativeBytes),
                sha256(generatedCBytes),
                nativeBytes.length,
                generatedCBytes.length,
                fallbackOccurrences,
                classMagicOccurrences,
                legacyMetadataOccurrences,
                legacyDecodeOccurrences,
                surface.nativePrintableStringCount(),
                surface.generatedCStringLiteralCount(),
                surface.generatedNativeTextCipherArrayCount(),
                surface.generatedNativeTextSiteCodecCount(),
                surface.generatedNativeTextCodecFamilyCount(),
                surface.generatedNativeTextDecoderCount(),
                surface.generatedNativeTextLargestDecoderFanout(),
                surface.generatedNativeTextFixedShapeOccurrences(),
                surface.generatedNativeTextAdjacentSeedCipherOccurrences(),
                surface.sensitivePlaintextMetrics(),
                sourceAudit.findings().stream()
                        .map(finding -> finding.code())
                        .toList(),
                sourceAudit.evidence(),
                symbolAudit.actualExports(),
                symbolAudit.unexpectedExports(),
                symbolAudit.missingExports(),
                passed);
    }

    private int findingCount(
            GeneratedNativeHardeningAuditResult result,
            String code) {
        return (int) result.findings().stream()
                .filter(finding -> finding.code().equals(code))
                .count();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
