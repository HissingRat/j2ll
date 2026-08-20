package xyz.melodysky.toolchain;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Proves entry reachability and the permitted call sequence on every local return path. */
final class NativeRegistrationAssemblyEntryFlowVerifier {
    private final NativeRegistrationAssemblyIndex index;
    private final NativeRegistrationAssemblyInstructionSet instructions;

    NativeRegistrationAssemblyEntryFlowVerifier(
            NativeRegistrationAssemblyIndex index) {
        this.index = index;
        this.instructions = new NativeRegistrationAssemblyInstructionSet(index);
    }

    void verifyRoot(
            String symbol,
            String route0,
            String route1) throws IOException {
        verify(
                index.function(symbol),
                new Policy(
                        Mode.ROOT,
                        List.of(List.of(route0), List.of(route1)),
                        List.of(),
                        0));
    }

    void verifySingleCall(
            String symbol,
            String target) throws IOException {
        verify(
                index.function(symbol),
                new Policy(
                        Mode.SINGLE,
                        List.of(List.of(target)),
                        List.of(),
                        0));
    }

    void verifyChunk(
            String symbol,
            List<String> owners,
            String nextChunk) throws IOException {
        ArrayList<String> full = new ArrayList<>(owners);
        if (nextChunk != null) {
            full.add(nextChunk);
        }
        verify(
                index.function(symbol),
                new Policy(
                        Mode.CHUNK,
                        List.of(),
                        List.copyOf(full),
                        owners.size()));
    }

    private void verify(
            NativeRegistrationAssemblyIndex.Function function,
            Policy policy) throws IOException {
        HashSet<List<String>> terminalSequences = new HashSet<>();
        explore(
                function,
                policy,
                new State(0, List.of(), false),
                new HashSet<>(),
                new HashSet<>(),
                terminalSequences);
        if (policy.mode() == Mode.CHUNK) {
            if (!terminalSequences.contains(policy.linearCalls())) {
                fail("MISSING_FULL_SUCCESS_CALL_PATH", function.symbol());
            }
        } else if (!terminalSequences.containsAll(policy.alternatives())) {
            fail(
                    "MISSING_ENTRY_REACHABLE_CALL_PATH",
                    function.symbol() + " expected=" + policy.alternatives()
                            + " actual=" + terminalSequences);
        }
    }

    private void explore(
            NativeRegistrationAssemblyIndex.Function function,
            Policy policy,
            State state,
            Set<State> active,
            Set<State> complete,
            Set<List<String>> terminalSequences) throws IOException {
        List<NativeRegistrationAssemblyIndex.Instruction> body = function.instructions();
        if (state.instructionIndex() < 0 || state.instructionIndex() >= body.size()) {
            fail("ENTRY_CFG_FALLTHROUGH", function.symbol());
        }
        if (complete.contains(state)) {
            return;
        }
        if (!active.add(state)) {
            fail("CYCLIC_ENTRY_CFG", function.symbol());
        }
        NativeRegistrationAssemblyIndex.Instruction instruction =
                body.get(state.instructionIndex());
        if (instructions.isReturn(instruction)) {
            verifyTerminal(function, policy, state, terminalSequences);
            finish(state, active, complete);
            return;
        }
        if (instructions.isCall(instruction)) {
            String target = instructions.directTarget(instruction);
            if (target == null) {
                fail("INDIRECT_ENTRY_CALL", location(function, instruction));
            }
            explore(
                    function,
                    policy,
                    advance(function, policy, state, target),
                    active,
                    complete,
                    terminalSequences);
        } else if (instructions.isIndirectCall(instruction)
                || instructions.isIndirectBranch(instruction)) {
            fail("INDIRECT_ENTRY_CONTROL", location(function, instruction));
        } else if (instructions.isUnconditionalBranch(instruction)) {
            explore(
                    function,
                    policy,
                    state.withIndex(localTarget(function, instruction, false)),
                    active,
                    complete,
                    terminalSequences);
        } else if (instructions.isConditionalBranch(instruction)) {
            State branched = state.withConditionalSeen();
            explore(
                    function,
                    policy,
                    branched.withIndex(state.instructionIndex() + 1),
                    active,
                    complete,
                    terminalSequences);
            explore(
                    function,
                    policy,
                    branched.withIndex(localTarget(function, instruction, true)),
                    active,
                    complete,
                    terminalSequences);
        } else {
            explore(
                    function,
                    policy,
                    state.withIndex(state.instructionIndex() + 1),
                    active,
                    complete,
                    terminalSequences);
        }
        finish(state, active, complete);
    }

    private State advance(
            NativeRegistrationAssemblyIndex.Function function,
            Policy policy,
            State state,
            String target) throws IOException {
        ArrayList<String> calls = new ArrayList<>(state.calls());
        calls.add(target);
        List<String> sequence = List.copyOf(calls);
        if (policy.mode() == Mode.CHUNK) {
            if (!isPrefix(sequence, policy.linearCalls())) {
                fail("ENTRY_CALL_SEQUENCE", function.symbol() + " actual=" + sequence);
            }
            if (state.calls().size() > 0
                    && state.calls().size() <= policy.ownerCallCount()
                    && !state.conditionalSeen()) {
                fail("OWNER_SUCCESS_EDGE_BYPASS", function.symbol() + " -> " + target);
            }
        } else {
            boolean prefix = policy.alternatives().stream()
                    .anyMatch(alternative -> isPrefix(sequence, alternative));
            if (!prefix) {
                fail("ENTRY_CALL_SEQUENCE", function.symbol() + " actual=" + sequence);
            }
            if (policy.mode() == Mode.ROOT
                    && state.calls().isEmpty()
                    && !state.conditionalSeen()) {
                fail("ROOT_CALL_WITHOUT_CONDITIONAL_ROUTE", function.symbol());
            }
        }
        return new State(state.instructionIndex() + 1, sequence, false);
    }

    private void verifyTerminal(
            NativeRegistrationAssemblyIndex.Function function,
            Policy policy,
            State state,
            Set<List<String>> terminalSequences) throws IOException {
        boolean valid;
        if (policy.mode() == Mode.CHUNK) {
            boolean full = state.calls().equals(policy.linearCalls())
                    && (policy.linearCalls().size() > policy.ownerCallCount()
                            || state.conditionalSeen());
            boolean ownerFailure = !state.calls().isEmpty()
                    && state.calls().size() <= policy.ownerCallCount()
                    && state.conditionalSeen();
            valid = full || ownerFailure;
        } else {
            valid = policy.alternatives().contains(state.calls());
        }
        if (!valid) {
            fail(
                    "ENTRY_RETURN_CALL_SEQUENCE",
                    function.symbol() + " actual=" + state.calls());
        }
        terminalSequences.add(state.calls());
    }

    private boolean isPrefix(List<String> prefix, List<String> full) {
        return prefix.size() <= full.size()
                && full.subList(0, prefix.size()).equals(prefix);
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

    private String location(
            NativeRegistrationAssemblyIndex.Function function,
            NativeRegistrationAssemblyIndex.Instruction instruction) {
        return function.symbol() + "@" + function.source().getFileName()
                + ":" + instruction.lineNumber();
    }

    private void fail(String code, String detail) throws IOException {
        throw NativeRegistrationAssemblyIndex.failure(code, detail);
    }

    private enum Mode { ROOT, SINGLE, CHUNK }

    private record Policy(
            Mode mode,
            List<List<String>> alternatives,
            List<String> linearCalls,
            int ownerCallCount) {}

    private record State(
            int instructionIndex,
            List<String> calls,
            boolean conditionalSeen) {
        private State {
            calls = List.copyOf(calls);
        }

        private State withIndex(int next) {
            return new State(next, calls, conditionalSeen);
        }

        private State withConditionalSeen() {
            return new State(instructionIndex, calls, true);
        }
    }
}
