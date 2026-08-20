package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeRegistrationOptimizedAssemblyPolicyBindingTest {
    @TempDir
    Path temp;

    @Test
    void gateRequiresAndEnforcesTheExactRegistrationCInputBinding()
            throws Exception {
        TargetTriple target = TargetTriple.LINUX_X64;
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        Path wrapper = workspace.jniDirectory().resolve("wrapper.c");
        Path runtime = workspace.runtimeDirectory().resolve("runtime.c");
        ZigInputSet inputs = new ZigInputSet(new ZigSourceSet(
                List.of(),
                List.of(wrapper, runtime),
                List.of(),
                List.of()));
        NativeBuildPlan buildPlan = new NativeBuildPlan(List.of(new NativeBuildUnit(
                target,
                temp.resolve("native").resolve(target.libraryFileName()),
                "j2lltest")));
        NativeRegistrationControlTopologyPlan topology =
                NativeRegistrationControlTestFixture.emission(
                                11,
                                "optimized-assembly-policy-binding")
                        .topologyPlan();
        writeEvidence(workspace, buildPlan, inputs, wrapper, target, topology);

        NativeRegistrationOptimizedAssemblyGate gate =
                new NativeRegistrationOptimizedAssemblyGate();
        assertFailure(
                () -> gate.verify(
                        workspace,
                        buildPlan,
                        inputs,
                        ZigCInputMachinePolicyPlan.defaults(inputs),
                        topology),
                "REGISTRATION_C_INPUT_POLICY_CLOSURE");
        assertFailure(
                () -> gate.verify(
                        workspace,
                        buildPlan,
                        inputs,
                        ZigCInputMachinePolicyPlan.forRegistrationWrapper(inputs, runtime),
                        topology),
                "REGISTRATION_SYMBOL_WRONG_C_INPUT");
        assertDoesNotThrow(() -> gate.verify(
                workspace,
                buildPlan,
                inputs,
                ZigCInputMachinePolicyPlan.forRegistrationWrapper(inputs, wrapper),
                topology));
    }

    @Test
    void nonzeroFailureLeavesMustRemainInTheForbiddenWrapperEvidence()
            throws Exception {
        TargetTriple target = TargetTriple.LINUX_X64;
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp);
        Path wrapper = workspace.jniDirectory().resolve("wrapper.c");
        Path runtime = workspace.runtimeDirectory().resolve("runtime.c");
        ZigInputSet inputs = new ZigInputSet(new ZigSourceSet(
                List.of(), List.of(wrapper, runtime), List.of(), List.of()));
        NativeBuildPlan buildPlan = new NativeBuildPlan(List.of(new NativeBuildUnit(
                target,
                temp.resolve("native").resolve(target.libraryFileName()),
                "j2lltest")));
        NativeRegistrationControlTopologyPlan topology =
                NativeRegistrationControlTestFixture.emission(
                                11,
                                "optimized-assembly-failure-binding")
                        .topologyPlan();
        String assembly = new NativeRegistrationOptimizedAssemblyFixture()
                .assembly(target, topology);
        String failure = topology.failureSymbols().ownerRollback();
        String block = functionBlock(assembly, failure);
        ZigBuildProgressPlan.TargetPlan targetPlan =
                ZigBuildProgressPlan.forSources(buildPlan, inputs.sources()).targets().get(0);
        for (ZigBuildProgressPlan.CompileUnit unit : targetPlan.compileUnits()) {
            for (ZigBuildProgressPlan.CompileInput input : unit.inputs()) {
                Path evidence = ZigOptimizedAssemblyEvidence.path(workspace, target, input);
                Files.createDirectories(evidence.getParent());
                boolean registration = input.source().toAbsolutePath().normalize().equals(
                        wrapper.toAbsolutePath().normalize());
                Files.writeString(
                        evidence,
                        registration ? assembly.replace(block, "") : block);
            }
        }

        assertFailure(
                () -> new NativeRegistrationOptimizedAssemblyGate().verify(
                        workspace,
                        buildPlan,
                        inputs,
                        ZigCInputMachinePolicyPlan.forRegistrationWrapper(inputs, wrapper),
                        topology),
                "REGISTRATION_SYMBOL_WRONG_C_INPUT");
    }

    private void writeEvidence(
            ZigBuildWorkspace workspace,
            NativeBuildPlan buildPlan,
            ZigInputSet inputs,
            Path registrationSource,
            TargetTriple target,
            NativeRegistrationControlTopologyPlan topology) throws IOException {
        String registrationAssembly =
                new NativeRegistrationOptimizedAssemblyFixture().assembly(target, topology);
        ZigBuildProgressPlan.TargetPlan targetPlan =
                ZigBuildProgressPlan.forSources(buildPlan, inputs.sources()).targets().get(0);
        for (ZigBuildProgressPlan.CompileUnit unit : targetPlan.compileUnits()) {
            for (ZigBuildProgressPlan.CompileInput input : unit.inputs()) {
                Path evidence = ZigOptimizedAssemblyEvidence.path(
                        workspace,
                        target,
                        input);
                Files.createDirectories(evidence.getParent());
                boolean registration = input.source().toAbsolutePath().normalize().equals(
                        registrationSource.toAbsolutePath().normalize());
                Files.writeString(
                        evidence,
                        registration
                                ? registrationAssembly
                                : "OUTLINED_FUNCTION_91:\n\tretq\n");
            }
        }
    }

    private void assertFailure(ThrowingIo action, String reasonCode) {
        IOException failure = assertThrows(IOException.class, action::run);
        assertTrue(failure.getMessage().contains(reasonCode), failure.getMessage());
    }

    private String functionBlock(String assembly, String symbol) {
        int start = assembly.indexOf(symbol + ":\n");
        String endMarker = "\t.size\t" + symbol + ", .-orphan\n";
        int end = assembly.indexOf(endMarker, start);
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("missing fixture function: " + symbol);
        }
        return assembly.substring(start, end + endMarker.length());
    }

    @FunctionalInterface
    private interface ThrowingIo {
        void run() throws IOException;
    }
}
