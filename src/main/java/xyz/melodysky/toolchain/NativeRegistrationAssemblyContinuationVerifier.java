package xyz.melodysky.toolchain;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Proves the stack-local volatile continuation on every local path after one call. */
final class NativeRegistrationAssemblyContinuationVerifier {
    private final NativeRegistrationAssemblyInstructionSet instructions;
    private final NativeRegistrationAssemblyStackAccess stackAccess;

    NativeRegistrationAssemblyContinuationVerifier(
            NativeRegistrationAssemblyInstructionSet instructions,
            boolean x64) {
        this.instructions = instructions;
        this.stackAccess = new NativeRegistrationAssemblyStackAccess(x64);
    }

    Set<Integer> verify(
            NativeRegistrationAssemblyIndex.Function function,
            int callIndex) throws IOException {
        LinkedHashSet<Integer> reachable = new LinkedHashSet<>();
        explore(
                function,
                callIndex,
                new State(callIndex + 1, Set.of(), false),
                new HashSet<>(),
                new HashSet<>(),
                reachable);
        return Set.copyOf(reachable);
    }

    private void explore(
            NativeRegistrationAssemblyIndex.Function function,
            int callIndex,
            State state,
            Set<State> active,
            Set<State> complete,
            Set<Integer> reachable) throws IOException {
        List<NativeRegistrationAssemblyIndex.Instruction> body = function.instructions();
        if (state.instructionIndex() < 0 || state.instructionIndex() >= body.size()) {
            fail("MISSING_POST_CALL_RETURN", callLocation(function, body, callIndex));
        }
        if (complete.contains(state)) {
            return;
        }
        if (!active.add(state)) {
            fail("CYCLIC_POST_CALL_CFG", callLocation(function, body, callIndex));
        }
        reachable.add(state.instructionIndex());
        NativeRegistrationAssemblyIndex.Instruction instruction =
                body.get(state.instructionIndex());
        if (instructions.isReturn(instruction)) {
            if (!state.readBack()) {
                fail(
                        "MISSING_POST_CALL_CONTINUATION",
                        callLocation(function, body, callIndex)
                                + " -> return@" + instruction.lineNumber());
            }
            finish(state, active, complete);
            return;
        }
        if (instructions.isCall(instruction)
                || instructions.isIndirectCall(instruction)) {
            fail("POST_CALL_NESTED_CALL", location(function, instruction));
        }
        if (instructions.isUnconditionalBranch(instruction)) {
            explore(
                    function,
                    callIndex,
                    state.withIndex(localTarget(function, instruction, false)),
                    active,
                    complete,
                    reachable);
        } else if (instructions.isConditionalBranch(instruction)) {
            explore(
                    function,
                    callIndex,
                    state.withIndex(state.instructionIndex() + 1),
                    active,
                    complete,
                    reachable);
            explore(
                    function,
                    callIndex,
                    state.withIndex(localTarget(function, instruction, true)),
                    active,
                    complete,
                    reachable);
        } else {
            NativeRegistrationAssemblyStackAccess.Slot written =
                    stackAccess.writtenStackSlot(instruction);
            Set<NativeRegistrationAssemblyStackAccess.Base> mutated =
                    stackAccess.mutatedBases(instruction);
            if (!state.readBack() && (state.writtenStackSlots().stream()
                            .anyMatch(slot -> mutated.contains(slot.base()))
                    || written != null && mutated.contains(written.base()))) {
                fail("POST_CALL_STACK_BASE_MUTATION", location(function, instruction));
            }
            Set<NativeRegistrationAssemblyStackAccess.Slot> writes =
                    new LinkedHashSet<>(state.writtenStackSlots());
            if (written != null) {
                writes.add(written);
            }
            NativeRegistrationAssemblyStackAccess.Slot read =
                    stackAccess.readStackSlot(instruction);
            boolean readBack = state.readBack()
                    || read != null && state.writtenStackSlots().contains(read);
            explore(
                    function,
                    callIndex,
                    new State(state.instructionIndex() + 1, writes, readBack),
                    active,
                    complete,
                    reachable);
        }
        finish(state, active, complete);
    }

    private int localTarget(
            NativeRegistrationAssemblyIndex.Function function,
            NativeRegistrationAssemblyIndex.Instruction instruction,
            boolean conditional) throws IOException {
        String target = conditional
                ? instructions.conditionalTarget(instruction)
                : instructions.directTarget(instruction);
        Integer targetIndex = instructions.localTargetIndex(function, target);
        if (targetIndex == null || targetIndex < 0
                || targetIndex >= function.instructions().size()) {
            fail("UNKNOWN_LOCAL_BRANCH_TARGET", location(function, instruction));
        }
        return targetIndex;
    }

    private void finish(State state, Set<State> active, Set<State> complete) {
        active.remove(state);
        complete.add(state);
    }

    private String callLocation(
            NativeRegistrationAssemblyIndex.Function function,
            List<NativeRegistrationAssemblyIndex.Instruction> body,
            int callIndex) {
        return location(function, body.get(callIndex));
    }

    private String location(
            NativeRegistrationAssemblyIndex.Function function,
            NativeRegistrationAssemblyIndex.Instruction instruction) {
        return function.symbol() + "@" + function.source().getFileName()
                + ":" + instruction.lineNumber();
    }

    private void fail(String code, String detail) throws IOException {
        throw NativeRegistrationAssemblyIndex.failure(code, detail);
    }

    private record State(
            int instructionIndex,
            Set<NativeRegistrationAssemblyStackAccess.Slot> writtenStackSlots,
            boolean readBack) {
        private State {
            writtenStackSlots = Set.copyOf(writtenStackSlots);
        }

        private State withIndex(int next) {
            return new State(next, writtenStackSlots, readBack);
        }
    }
}
