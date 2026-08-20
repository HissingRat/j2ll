package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeRegistrationControlCrossTargetMachineShapeTest {
    @TempDir
    Path temp;

    @Test
    void realManagedZigPreservesRoutesAndThreeOrEightChunksAcrossAllSixTargets()
            throws Exception {
        NativeRegistrationControlMachineShapeFixture fixture =
                new NativeRegistrationControlMachineShapeFixture(temp);
        Path zig = fixture.realManagedZig();
        Path include = fixture.prepareJniHeaders();

        for (int ownerCount : List.of(11, 33)) {
            HostNativeRegistrationSource.Emission emission =
                    NativeRegistrationControlTestFixture.emission(
                            ownerCount,
                            "registration-six-target-machine-shape-"
                                    + ownerCount);
            NativeRegistrationControlTopologyPlan plan =
                    emission.topologyPlan();
            assertEquals(3, plan.routePlan().routes().size());
            assertEquals(ownerCount == 11 ? 3 : 8, plan.chunks().size());
            String translationUnit =
                    new NativeRegistrationControlChunkExecutionFixture()
                            .harness(
                                    ownerCount,
                                    emission.source(),
                                    plan.chunks().get(0).endExclusive(),
                                    plan.chunks().get(1).startInclusive() + 1,
                                    plan.chunks().get(2).startInclusive() + 1);
            Path source = temp.resolve(
                    "registration-machine-shape-"
                            + ownerCount
                            + ".c");
            Files.writeString(
                    source,
                    translationUnit,
                    StandardCharsets.UTF_8);

            for (TargetTriple target : TargetTriple.values()) {
                Path assemblyPath = fixture.compileAssembly(
                        zig,
                        include,
                        source,
                        target,
                        Integer.toString(ownerCount));
                try {
                    new NativeRegistrationOptimizedAssemblyGate().verifyTarget(
                            target,
                            List.of(assemblyPath),
                            plan);
                } catch (Exception exception) {
                    throw new java.io.IOException(
                            "ownerCount=" + ownerCount
                                    + " target=" + target
                                    + " assembly=" + assemblyPath
                                    + ": " + exception.getMessage(),
                            exception);
                }
                String assembly = Files.readString(
                        assemblyPath,
                        StandardCharsets.UTF_8);
                new NativeRegistrationAssemblyShapeAssertions().assertShape(
                        assembly,
                        target,
                        plan);
            }
        }
    }
}
