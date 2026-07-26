package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.config.BinaryProtectionConfig;
import xyz.melodysky.config.IntermediatesConfig;
import xyz.melodysky.config.IrProtectionConfig;
import xyz.melodysky.config.LlvmProtectionConfig;
import xyz.melodysky.config.ProtectionConfig;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.config.SignaturePolicy;
import xyz.melodysky.config.TargetConfig;
import xyz.melodysky.toolchain.TargetTriple;

class J2llMetadataEntriesTest {
    @Test
    void reportsManifestRequiresFieldInternalizationReport() {
        byte[] manifestBytes = new J2llMetadataEntries()
                .entries(config(), Optional.empty())
                .get("META-INF/j2ll/reports-manifest.json");
        var reports = JsonParser.parseString(new String(manifestBytes, StandardCharsets.UTF_8))
                .getAsJsonObject()
                .getAsJsonArray("reports");

        assertTrue(java.util.stream.StreamSupport.stream(reports.spliterator(), false)
                .anyMatch(report -> report.getAsString().equals("field-internalization-report.json")));
    }

    private ResolvedConfig config() {
        TargetConfig target = TargetConfig.single(TargetTriple.LINUX_X64);
        return new ResolvedConfig(
                1,
                Path.of("input.jar"),
                List.of(),
                null,
                null,
                AnalysisWorld.PARTIAL_WORLD,
                Path.of("out"),
                List.of(),
                List.of(),
                target,
                target.enabledTargets(),
                "native0",
                SignaturePolicy.FAIL,
                null,
                new IntermediatesConfig(false, false, false, false, false),
                new ProtectionConfig(
                        false,
                        "seed",
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
                        new LlvmProtectionConfig(false, false, false, false, false, false),
                        new BinaryProtectionConfig(false, false, false, false, false)));
    }
}
