package xyz.melodysky.toolchain;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies direct control edges and continuations inside one indexed assembly function. */
final class NativeRegistrationAssemblyFunctionVerifier {
    private final NativeRegistrationAssemblyIndex index;
    private final NativeRegistrationAssemblyInstructionSet instructions;
    private final NativeRegistrationAssemblyContinuationVerifier continuations;
    private final Set<String> controlSymbols;

    NativeRegistrationAssemblyFunctionVerifier(
            NativeRegistrationAssemblyIndex index,
            Set<String> controlSymbols) {
        this.index = index;
        this.instructions = new NativeRegistrationAssemblyInstructionSet(index);
        this.continuations = new NativeRegistrationAssemblyContinuationVerifier(
                instructions,
                index.archClassifier().equals("x64"));
        this.controlSymbols = Set.copyOf(controlSymbols);
    }

    NativeRegistrationAssemblyInstructionSet.ContinuationProfile verifyStrict(
            String symbol,
            List<String> expectedCalls,
            boolean orderIndependent,
            boolean conditionalRequired,
            Set<String> continuationTargets) throws IOException {
        NativeRegistrationAssemblyIndex.Function function = index.function(symbol);
        List<NativeRegistrationAssemblyIndex.Instruction> body = function.instructions();
        ArrayList<String> calls = new ArrayList<>();
        ArrayList<Integer> callIndexes = new ArrayList<>();
        boolean hasConditional = false;
        boolean hasReturn = false;
        for (int instructionIndex = 0; instructionIndex < body.size(); instructionIndex++) {
            NativeRegistrationAssemblyIndex.Instruction instruction = body.get(instructionIndex);
            if (instructions.isCall(instruction)) {
                String target = instructions.directTarget(instruction);
                if (target == null) {
                    fail("INDIRECT_CONTROL_EDGE", location(function, instruction));
                }
                calls.add(target);
                callIndexes.add(instructionIndex);
                continue;
            }
            if (instructions.isIndirectCall(instruction)
                    || instructions.isIndirectBranch(instruction)) {
                fail("INDIRECT_CONTROL_EDGE", location(function, instruction));
            }
            if (instructions.isUnconditionalBranch(instruction)) {
                String target = instructions.directTarget(instruction);
                if (!instructions.isLocalTarget(function, target)) {
                    fail("UNCONDITIONAL_TAIL_EDGE", location(function, instruction));
                }
            } else if (instructions.isConditionalBranch(instruction)) {
                hasConditional = true;
                String target = instructions.conditionalTarget(instruction);
                if (!instructions.isLocalTarget(function, target)) {
                    fail("NONLOCAL_CONDITIONAL_EDGE", location(function, instruction));
                }
            }
            if (instructions.isReturn(instruction)) {
                hasReturn = true;
            } else {
                rejectCodePointerReference(function, instruction);
            }
        }
        if (!(orderIndependent
                ? frequencies(calls).equals(frequencies(expectedCalls))
                : calls.equals(expectedCalls))) {
            fail(
                    "DIRECT_CALL_CLOSURE",
                    symbol + " expected=" + expectedCalls + " actual=" + calls);
        }
        if (conditionalRequired && !hasConditional) {
            fail("MISSING_CONDITIONAL_ROUTE", symbol);
        }
        if (!hasReturn) {
            fail("MISSING_RETURN", symbol);
        }
        Set<String> verifiedContinuationTargets = new java.util.HashSet<>();
        NativeRegistrationAssemblyInstructionSet.ContinuationProfile profile = null;
        for (int ordinal = 0; ordinal < callIndexes.size(); ordinal++) {
            String target = calls.get(ordinal);
            if (!continuationTargets.contains(target)) {
                continue;
            }
            if (!verifiedContinuationTargets.add(target)) {
                fail("AMBIGUOUS_CONTINUATION_CALL_TARGET", symbol + " -> " + target);
            }
            Set<Integer> reachable = continuations.verify(
                    function,
                    callIndexes.get(ordinal));
            profile = instructions.continuationProfile(function, reachable);
        }
        if (!verifiedContinuationTargets.equals(continuationTargets)) {
            fail(
                    "MISSING_CONTINUATION_CALL_TARGET",
                    symbol + " expected=" + continuationTargets
                            + " actual=" + verifiedContinuationTargets);
        }
        if (continuationTargets.size() != 1 || profile == null) {
            return new NativeRegistrationAssemblyInstructionSet.ContinuationProfile(
                    List.of(), Set.of(), 0, false, false);
        }
        return profile;
    }

    void verifyAggregate(
            String aggregateSymbol,
            String firstChunkSymbol,
            Set<String> coreSymbols) throws IOException {
        NativeRegistrationAssemblyIndex.Function aggregate = index.function(aggregateSymbol);
        Map<String, Integer> actual = new HashMap<>();
        for (NativeRegistrationAssemblyIndex.Instruction instruction : aggregate.instructions()) {
            if (!instructions.isCall(instruction)) {
                continue;
            }
            String target = instructions.directTarget(instruction);
            if (target != null && coreSymbols.contains(target)) {
                actual.merge(target, 1, Integer::sum);
            }
        }
        Map<String, Integer> expected = firstChunkSymbol == null
                ? Map.of()
                : Map.of(firstChunkSymbol, 1);
        if (!actual.equals(expected)) {
            fail(
                    "AGGREGATE_CHUNK_ENTRY_CLOSURE",
                    "expected=" + expected + " actual=" + actual);
        }
    }

    private void rejectCodePointerReference(
            NativeRegistrationAssemblyIndex.Function function,
            NativeRegistrationAssemblyIndex.Instruction instruction) throws IOException {
        for (String token : instructions.identifierTokens(instruction.operands())) {
            if (controlSymbols.contains(index.canonicalSymbol(token))) {
                fail(
                        "CONTROL_CODE_POINTER_REFERENCE",
                        location(function, instruction));
            }
        }
    }

    private Map<String, Integer> frequencies(List<String> values) {
        HashMap<String, Integer> result = new HashMap<>();
        for (String value : values) {
            result.merge(value, 1, Integer::sum);
        }
        return result;
    }

    private String location(
            NativeRegistrationAssemblyIndex.Function function,
            NativeRegistrationAssemblyIndex.Instruction instruction) {
        return function.symbol()
                + "@" + function.source().getFileName()
                + ":" + instruction.lineNumber();
    }

    private void fail(String code, String detail) throws IOException {
        throw NativeRegistrationAssemblyIndex.failure(code, detail);
    }
}
