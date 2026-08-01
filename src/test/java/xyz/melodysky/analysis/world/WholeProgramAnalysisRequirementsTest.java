package xyz.melodysky.analysis.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
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

class WholeProgramAnalysisRequirementsTest {
    private final WholeProgramAnalysisRequirements requirements =
            new WholeProgramAnalysisRequirements();

    @Test
    void fieldInternalizationRequirementIsFeatureScopedAndPromptIsStable() {
        ResolvedConfig config = config(
                AnalysisWorld.PARTIAL_WORLD,
                true,
                true,
                true);

        var result = requirements.forConfig(config);

        assertEquals(1, result.size());
        assertEquals(
                WholeProgramAnalysisFeature.FIELD_INTERNALIZATION,
                result.get(0).feature());
        assertTrue(result.get(0).feature().currentJarOnlySupported());
        assertEquals(
                "fieldInternalization requires CLOSED_WORLD, continue? (Y/N)",
                result.get(0).prompt());
        assertEquals(
                1,
                requirements.unmet(config, WholeProgramAnalysisPolicy.strict()).size());
        assertTrue(requirements.unmet(
                        config,
                        WholeProgramAnalysisPolicy.currentJarOnly(
                                List.of(WholeProgramAnalysisFeature.FIELD_INTERNALIZATION)))
                .isEmpty());
    }

    @Test
    void methodInternalizationRequirementDescribesCurrentJarOnlyBlindSpots() {
        ResolvedConfig config = config(
                AnalysisWorld.PARTIAL_WORLD,
                true,
                true,
                false,
                true);

        var result = requirements.forConfig(config);

        assertEquals(1, result.size());
        assertEquals(
                WholeProgramAnalysisFeature.METHOD_INTERNALIZATION,
                result.get(0).feature());
        assertEquals(
                "methodInternalization requires CLOSED_WORLD, continue? (Y/N)",
                result.get(0).prompt());
        assertTrue(result.get(0).warning().contains("call sites and overrides only inside the current input JAR"));
        assertTrue(result.get(0).warning().contains("external callers, subclasses, reflection/JNI/agent observers"));
        assertTrue(result.get(0).warning().contains(
                "Exact-allowlisted public static methods may use this approved scope"));
        assertTrue(result.get(0).warning().contains(
                "public instance methods still require declared CLOSED_WORLD"));
        assertEquals(1, requirements.unmet(config, WholeProgramAnalysisPolicy.strict()).size());
        assertTrue(requirements.unmet(
                        config,
                        WholeProgramAnalysisPolicy.currentJarOnly(
                                List.of(WholeProgramAnalysisFeature.METHOD_INTERNALIZATION)))
                .isEmpty());
    }

    @Test
    void enabledWholeProgramFeaturesHaveIndependentRequirementsInStableOrder() {
        ResolvedConfig config = config(
                AnalysisWorld.PARTIAL_WORLD,
                true,
                true,
                true,
                true);

        assertEquals(
                List.of(
                        WholeProgramAnalysisFeature.FIELD_INTERNALIZATION,
                        WholeProgramAnalysisFeature.METHOD_INTERNALIZATION),
                requirements.forConfig(config).stream()
                        .map(WholeProgramAnalysisRequirement::feature)
                        .toList());
    }

    @Test
    void closedWorldAndDisabledProtectionLayersNeedNoConfirmation() {
        assertTrue(requirements.forConfig(config(
                        AnalysisWorld.CLOSED_WORLD,
                        true,
                        true,
                        true))
                .isEmpty());
        assertTrue(requirements.forConfig(config(
                        AnalysisWorld.UNKNOWN_DYNAMIC_WORLD,
                        false,
                        true,
                        true))
                .isEmpty());
        assertTrue(requirements.forConfig(config(
                        AnalysisWorld.JDK_EXTERNAL_WORLD,
                        true,
                        false,
                        true))
                .isEmpty());
        assertTrue(requirements.forConfig(config(
                        AnalysisWorld.PARTIAL_WORLD,
                        true,
                        true,
                        false))
                .isEmpty());
    }

    private ResolvedConfig config(
            AnalysisWorld world,
            boolean protectionEnabled,
            boolean irEnabled,
            boolean fieldInternalization) {
        return config(
                world,
                protectionEnabled,
                irEnabled,
                fieldInternalization,
                false);
    }

    private ResolvedConfig config(
            AnalysisWorld world,
            boolean protectionEnabled,
            boolean irEnabled,
            boolean fieldInternalization,
            boolean methodInternalization) {
        TargetConfig target = TargetConfig.single(TargetTriple.LINUX_X64);
        return new ResolvedConfig(
                1,
                Path.of("input.jar"),
                List.of(),
                null,
                null,
                world,
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
                        protectionEnabled,
                        "seed",
                        new IrProtectionConfig(
                                irEnabled,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                fieldInternalization,
                                methodInternalization,
                                false,
                                false),
                        new LlvmProtectionConfig(false, false, false, false, false, false),
                        new BinaryProtectionConfig(false, false, false, false, false)));
    }
}
