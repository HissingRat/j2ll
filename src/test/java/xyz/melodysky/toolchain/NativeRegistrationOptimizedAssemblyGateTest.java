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

final class NativeRegistrationOptimizedAssemblyGateTest {
    @TempDir
    Path temp;

    @Test
    void acceptsExactNonTailTopologyForAllSixAssemblyDialects() throws Exception {
        for (int ownerCount : List.of(0, 11)) {
            NativeRegistrationControlTopologyPlan plan = plan(ownerCount);
            for (TargetTriple target : TargetTriple.values()) {
                Path evidence = write(target, assembly(target, plan));
                assertDoesNotThrow(() -> new NativeRegistrationOptimizedAssemblyGate()
                        .verifyTarget(target, List.of(evidence), plan));
            }
            if (ownerCount > 0) {
                TargetTriple x64Target = TargetTriple.WINDOWS_X64;
                Path x64Evidence = write(
                        x64Target,
                        assembly(x64Target, plan)
                                .replace("\tmovslq\t%eax, %rax", "\tcltq"));
                assertDoesNotThrow(() -> new NativeRegistrationOptimizedAssemblyGate()
                        .verifyTarget(x64Target, List.of(x64Evidence), plan));
                TargetTriple arm64Target = TargetTriple.WINDOWS_ARM64;
                Path arm64Evidence = write(
                        arm64Target,
                        assembly(arm64Target, plan).replace(
                                "\tlsr\tx10, x10, #7",
                                "\teor\tx10, x10, x10, lsr #7"));
                assertDoesNotThrow(() -> new NativeRegistrationOptimizedAssemblyGate()
                        .verifyTarget(
                                arm64Target,
                                List.of(arm64Evidence),
                                plan));
            }
        }
    }

    @Test
    void rejectsTailCollapsedRootUnknownOutlinerAndCodePointer() throws Exception {
        NativeRegistrationControlTopologyPlan plan = plan(11);
        TargetTriple target = TargetTriple.LINUX_X64;
        String valid = assembly(target, plan);
        String route0 = plan.routePlan().route(0).symbol();
        String route1 = plan.routePlan().route(1).symbol();

        assertFailure(
                target,
                plan,
                valid.replace("\tcallq\t" + route0, "\tjmp\t" + route0),
                "UNCONDITIONAL_TAIL_EDGE");
        assertFailure(
                target,
                plan,
                valid.replace(
                        "\tcallq\t" + route0 + "\n\tjmp\t.Lroot_done",
                        "\tcallq\t" + route0
                                + "\n\tcallq\tOUTLINED_FUNCTION_0"
                                + "\n\tjmp\t.Lroot_done"),
                "MACHINE_OUTLINER_ARTIFACT");
        assertFailure(
                target,
                plan,
                valid.replace(
                        "\ttestl\t%eax, %eax\n\tjne\t.Lroot_else",
                        "\tleaq\t" + route1 + "(%rip), %r11\n"
                                + "\ttestl\t%eax, %eax\n\tjne\t.Lroot_else"),
                "CONTROL_CODE_POINTER_REFERENCE");
    }

    @Test
    void rejectsOutlinedArtifactsAnywhereInTheRegistrationEvidence() throws Exception {
        NativeRegistrationControlTopologyPlan plan = plan(11);
        TargetTriple target = TargetTriple.LINUX_X64;
        String valid = assembly(target, plan);
        String chunk0 = plan.chunks().get(0).symbol();
        String owner0 = plan.owners().get(0).symbol();
        String failure = plan.failureSymbols().ownerRollback();

        assertFailure(
                target,
                plan,
                valid.replace(
                        "\tcallq\t" + chunk0,
                        "\tcallq\t" + chunk0
                                + "\n\tcallq\tOUTLINED_FUNCTION_17"),
                "MACHINE_OUTLINER_ARTIFACT");
        assertFailure(
                target,
                plan,
                valid.replace(
                        owner0 + ":\n",
                        owner0 + ":\n\tcallq\tOUTLINED_FUNCTION_23\n"),
                "MACHINE_OUTLINER_ARTIFACT");
        assertFailure(
                target,
                plan,
                valid.replace(
                        failure + ":\n",
                        failure + ":\n\tcallq\tOUTLINED_FUNCTION_29\n"),
                "MACHINE_OUTLINER_ARTIFACT");
    }

    @Test
    void outlinerAuditIsCodeOnlyButIncludesUnindexedLabels() throws Exception {
        NativeRegistrationControlTopologyPlan plan = plan(11);
        TargetTriple target = TargetTriple.LINUX_X64;
        String valid = assembly(target, plan);
        Path annotated = write(
                target,
                valid.replace(
                        "JNI_OnLoad:\n",
                        "JNI_OnLoad:\n"
                                + "\t# OUTLINED_FUNCTION_41 is comment-only\n"
                                + "\t.asciz\t\"OUTLINED_FUNCTION_43\"\n"));
        assertDoesNotThrow(() -> new NativeRegistrationOptimizedAssemblyGate()
                .verifyTarget(target, List.of(annotated), plan));
        assertFailure(
                target,
                plan,
                valid + "\n.L_OUTLINED_FUNCTION_47:\n\tretq\n",
                "MACHINE_OUTLINER_ARTIFACT");
    }

    @Test
    void rejectsMissingDuplicateAndAliasedDefinitions() throws Exception {
        NativeRegistrationControlTopologyPlan plan = plan(11);
        TargetTriple target = TargetTriple.LINUX_ARM64;
        String valid = assembly(target, plan);
        String route2 = plan.routePlan().route(2).symbol();
        String label = route2 + ":\n";

        assertFailure(target, plan, valid.replace(label, ""), "MISSING_FUNCTION_DEFINITION");
        Path first = write(target, valid);
        Path duplicate = temp.resolve("duplicate.s");
        Files.writeString(duplicate, valid);
        IOException duplicated = assertThrows(
                IOException.class,
                () -> new NativeRegistrationOptimizedAssemblyGate()
                        .verifyTarget(target, List.of(first, duplicate), plan));
        assertTrue(duplicated.getMessage().contains("DUPLICATE_FUNCTION_DEFINITION"));
        assertFailure(
                target,
                plan,
                valid.replace(label, route2 + ":\n" + plan.aggregateSymbol() + ":\n"),
                "EMPTY_OR_ALIASED_FUNCTION_RANGE");
    }

    @Test
    void rejectsEachRootBranchBypassAndMissingStackReadback() throws Exception {
        NativeRegistrationControlTopologyPlan plan = plan(11);
        TargetTriple target = TargetTriple.LINUX_X64;
        String valid = assembly(target, plan);
        String route0Call = "\tcallq\t" + plan.routePlan().route(0).symbol();
        String route1Call = "\tcallq\t" + plan.routePlan().route(1).symbol();
        String withBypassBlock = valid.replace(
                "\tretq\n\t.size\tJNI_OnLoad, .-orphan",
                "\tretq\n.Lroot_bypass:\n\tretq\n"
                        + "\t.size\tJNI_OnLoad, .-orphan");

        assertFailure(
                target,
                plan,
                withBypassBlock.replace(
                        route0Call + "\n\tjmp\t.Lroot_done",
                        route0Call + "\n\tjmp\t.Lroot_bypass"),
                "MISSING_POST_CALL_CONTINUATION");
        assertFailure(
                target,
                plan,
                withBypassBlock.replace(
                        route1Call + "\n.Lroot_done:",
                        route1Call + "\n\tjmp\t.Lroot_bypass\n.Lroot_done:"),
                "MISSING_POST_CALL_CONTINUATION");
        assertFailure(
                target,
                plan,
                valid.replace(
                        "\tmovl\t-4(%rsp), %eax\n\tretq",
                        "\tmovl\t%eax, %eax\n\tretq"),
                "MISSING_POST_CALL_CONTINUATION");
    }

    @Test
    void rejectsEntryBypassOfRootRouteAndForwardChunk() throws Exception {
        NativeRegistrationControlTopologyPlan plan = plan(11);
        TargetTriple target = TargetTriple.LINUX_X64;
        String valid = assembly(target, plan);
        String route = plan.routePlan().route(0).symbol();
        NativeRegistrationControlTopologyPlan.Chunk chunk = plan.chunks().get(0);
        String next = plan.chunks().get(1).symbol();

        assertFailure(
                target,
                plan,
                withReturnBlock(valid, "JNI_OnLoad", ".Lroot_entry_bypass")
                        .replace(
                                "JNI_OnLoad:\n",
                                "JNI_OnLoad:\n\ttestl\t%eax, %eax\n"
                                        + "\tjne\t.Lroot_entry_bypass\n"),
                "ENTRY_RETURN_CALL_SEQUENCE");
        assertFailure(
                target,
                plan,
                withReturnBlock(valid, route, ".Lroute_entry_bypass")
                        .replace(
                                route + ":\n",
                                route + ":\n\ttestl\t%eax, %eax\n"
                                        + "\tjne\t.Lroute_entry_bypass\n"),
                "ENTRY_RETURN_CALL_SEQUENCE");
        assertFailure(
                target,
                plan,
                withReturnBlock(valid, chunk.symbol(), ".Lchunk_forward_bypass")
                        .replace(
                                "\tcallq\t" + next,
                                "\tjmp\t.Lchunk_forward_bypass\n\tcallq\t" + next),
                "MISSING_FULL_SUCCESS_CALL_PATH");
    }

    private NativeRegistrationControlTopologyPlan plan(int ownerCount) {
        return NativeRegistrationControlTestFixture.emission(
                        ownerCount,
                        "optimized-assembly-gate-" + ownerCount)
                .topologyPlan();
    }

    private void assertFailure(
            TargetTriple target,
            NativeRegistrationControlTopologyPlan plan,
            String assembly,
            String code) throws Exception {
        IOException failure = assertThrows(
                IOException.class,
                () -> new NativeRegistrationOptimizedAssemblyGate()
                        .verifyTarget(target, List.of(write(target, assembly)), plan));
        assertTrue(failure.getMessage().contains(code), failure.getMessage());
    }

    private Path write(TargetTriple target, String content) throws IOException {
        Path path = temp.resolve(target.directoryName() + "-" + System.nanoTime() + ".s");
        Files.writeString(path, content);
        return path;
    }

    private String assembly(
            TargetTriple target,
            NativeRegistrationControlTopologyPlan plan) {
        return new NativeRegistrationOptimizedAssemblyFixture().assembly(target, plan);
    }

    private String withReturnBlock(
            String assembly,
            String function,
            String label) {
        return assembly.replace(
                "\t.size\t" + function + ", .-orphan",
                label + ":\n\tretq\n\t.size\t" + function + ", .-orphan");
    }
}
