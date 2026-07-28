package xyz.melodysky.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.config.BinaryProtectionConfig;
import xyz.melodysky.config.IrProtectionConfig;
import xyz.melodysky.config.LlvmProtectionConfig;
import xyz.melodysky.config.ProtectionConfig;
import xyz.melodysky.config.ProtectionSeedMode;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

final class BuildProtectionMaterialsPlanTest {
    @Test
    void dualPlanChangesProductionWrapperAndMethodTableIdentifiers() {
        String rawFirst = "release-root-first-must-not-leak";
        String rawSecond = "release-root-second-must-not-leak";
        BuildProtectionMaterials first = materials(rawFirst);
        BuildProtectionMaterials repeated = materials(rawFirst);
        BuildProtectionMaterials second = materials(rawSecond);
        List<MethodRewriteDecision> decisions = decisions();

        NativeRegistrationPlan firstRegistration =
                new NativeRegistrationPlanner().plan(
                        decisions,
                        first.wrapperSeed());
        NativeRegistrationPlan repeatedRegistration =
                new NativeRegistrationPlanner().plan(
                        decisions,
                        repeated.wrapperSeed());
        NativeRegistrationPlan secondRegistration =
                new NativeRegistrationPlanner().plan(
                        decisions,
                        second.wrapperSeed());
        MethodTableHidingPlan firstTable =
                new MethodTableHidingPlanner().plan(
                        firstRegistration,
                        true,
                        first.methodTableSeed());
        MethodTableHidingPlan repeatedTable =
                new MethodTableHidingPlanner().plan(
                        repeatedRegistration,
                        true,
                        repeated.methodTableSeed());
        MethodTableHidingPlan secondTable =
                new MethodTableHidingPlanner().plan(
                        secondRegistration,
                        true,
                        second.methodTableSeed());

        assertEquals(firstRegistration, repeatedRegistration);
        assertEquals(firstTable, repeatedTable);
        assertNotEquals(
                firstRegistration.entries().get(0).nativeSymbol(),
                secondRegistration.entries().get(0).nativeSymbol());
        assertNotEquals(firstTable.planId(), secondTable.planId());
        assertFalse(firstRegistration.toString().contains(rawFirst));
        assertFalse(firstTable.toString().contains(rawFirst));
        assertFalse(secondRegistration.toString().contains(rawSecond));
        assertFalse(secondTable.toString().contains(rawSecond));
    }

    private List<MethodRewriteDecision> decisions() {
        var parsed = new AsmClassParser()
                .parse(new ClassFileEntry(
                        "sample/IdentityFixture.class",
                        AsmFixtureBuilder.classWithIntMethod(
                                "sample/IdentityFixture",
                                "value",
                                7),
                        "fixture"))
                .artifact()
                .orElseThrow();
        return new MethodRewritePlanner().planClass(parsed).stream()
                .filter(decision -> !decision.method().name().equals("<init>"))
                .toList();
    }

    private BuildProtectionMaterials materials(String root) {
        ProtectionConfig config = new ProtectionConfig(
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
                new LlvmProtectionConfig(
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
        return BuildProtectionMaterials.derive(
                BuildProtectionIdentity.from(config));
    }
}
