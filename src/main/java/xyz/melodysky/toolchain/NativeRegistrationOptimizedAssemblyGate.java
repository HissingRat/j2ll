package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Fail-closed topology gate over the optimized assembly used by the actual native link. */
final class NativeRegistrationOptimizedAssemblyGate {
    private static final String ROOT = "JNI_OnLoad";

    void verify(
            ZigBuildWorkspace workspace,
            NativeBuildPlan buildPlan,
            ZigInputSet inputs,
            ZigCInputMachinePolicyPlan machinePolicies,
            NativeRegistrationControlTopologyPlan plan) throws IOException {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(buildPlan, "buildPlan");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(machinePolicies, "machinePolicies");
        Objects.requireNonNull(plan, "plan");
        Path registrationSource;
        try {
            registrationSource = machinePolicies.registrationControlSource();
        } catch (IllegalStateException exception) {
            throw NativeRegistrationAssemblyIndex.failure(
                    "REGISTRATION_C_INPUT_POLICY_CLOSURE",
                    exception.getMessage());
        }
        ZigSourceSet sources = inputs.sources();
        ZigBuildProgressPlan progress = ZigBuildProgressPlan.forSources(buildPlan, sources);
        for (ZigBuildProgressPlan.TargetPlan targetPlan : progress.targets()) {
            EvidenceBinding binding = evidence(workspace, targetPlan, registrationSource);
            verifyTargetBound(
                    targetPlan.target(),
                    binding.allEvidence(),
                    binding.registrationEvidence(),
                    plan);
        }
    }

    void verifyTarget(
            TargetTriple target,
            List<Path> evidence,
            NativeRegistrationControlTopologyPlan plan) throws IOException {
        verifyTargetBound(target, evidence, null, plan);
    }

    private void verifyTargetBound(
            TargetTriple target,
            List<Path> evidence,
            Path registrationEvidence,
            NativeRegistrationControlTopologyPlan plan) throws IOException {
        Set<String> coreSymbols = coreSymbols(plan);
        Set<String> bindingSymbols = bindingSymbols(plan, coreSymbols);
        NativeRegistrationAssemblyIndex index = NativeRegistrationAssemblyIndex.read(
                target,
                evidence,
                bindingSymbols);
        LinkedHashSet<Path> protectedEvidence = new LinkedHashSet<>();
        if (registrationEvidence != null) {
            Path expected = registrationEvidence.toAbsolutePath().normalize();
            for (String symbol : bindingSymbols) {
                if (!index.function(symbol).source().toAbsolutePath().normalize().equals(expected)) {
                    throw NativeRegistrationAssemblyIndex.failure(
                            "REGISTRATION_SYMBOL_WRONG_C_INPUT",
                            symbol);
                }
            }
            protectedEvidence.add(expected);
        } else {
            for (String symbol : bindingSymbols) {
                protectedEvidence.add(index.function(symbol).source()
                        .toAbsolutePath().normalize());
            }
        }
        new NativeRegistrationAssemblyArtifactVerifier()
                .rejectMachineOutlinerArtifacts(target, List.copyOf(protectedEvidence));
        NativeRegistrationAssemblyFunctionVerifier verifier =
                new NativeRegistrationAssemblyFunctionVerifier(index, coreSymbols);
        NativeRegistrationAssemblyEntryFlowVerifier entryFlow =
                new NativeRegistrationAssemblyEntryFlowVerifier(index);
        List<String> rootCalls = plan.routePlan().enabled()
                ? List.of(
                        plan.routePlan().route(0).symbol(),
                        plan.routePlan().route(1).symbol())
                : List.of(plan.aggregateSymbol());
        verifier.verifyStrict(
                ROOT,
                rootCalls,
                true,
                plan.routePlan().enabled(),
                Set.copyOf(rootCalls));
        if (plan.routePlan().enabled()) {
            entryFlow.verifyRoot(
                    ROOT,
                    plan.routePlan().route(0).symbol(),
                    plan.routePlan().route(1).symbol());
        } else {
            entryFlow.verifySingleCall(ROOT, plan.aggregateSymbol());
        }
        verifyRoutes(plan, verifier, entryFlow);
        verifyChunks(plan, verifier, entryFlow);
        verifier.verifyAggregate(
                plan.aggregateSymbol(),
                plan.chunks().isEmpty() ? null : plan.chunks().get(0).symbol(),
                coreSymbols);
    }

    private void verifyRoutes(
            NativeRegistrationControlTopologyPlan plan,
            NativeRegistrationAssemblyFunctionVerifier verifier,
            NativeRegistrationAssemblyEntryFlowVerifier entryFlow) throws IOException {
        HashSet<List<String>> continuationShapes = new HashSet<>();
        NativeRegistrationRouteAssemblyShapeVerifier shapeVerifier =
                new NativeRegistrationRouteAssemblyShapeVerifier();
        for (NativeRegistrationControlRoutePlan.Route route : plan.routePlan().routes()) {
            String expected = route.targetKind()
                            == NativeRegistrationControlRoutePlan.TargetKind.AGGREGATE
                    ? plan.aggregateSymbol()
                    : plan.routePlan().route(route.targetRouteOrdinal()).symbol();
            NativeRegistrationAssemblyInstructionSet.ContinuationProfile profile =
                    verifier.verifyStrict(
                            route.symbol(),
                            List.of(expected),
                            false,
                            false,
                            Set.of(expected));
            entryFlow.verifySingleCall(route.symbol(), expected);
            shapeVerifier.verify(route.postCallRecipe(), profile, route.symbol());
            requireDistinct(continuationShapes, profile.signature(), route.symbol());
        }
    }

    private void verifyChunks(
            NativeRegistrationControlTopologyPlan plan,
            NativeRegistrationAssemblyFunctionVerifier verifier,
            NativeRegistrationAssemblyEntryFlowVerifier entryFlow) throws IOException {
        HashSet<List<String>> continuationShapes = new HashSet<>();
        NativeRegistrationChunkAssemblyShapeVerifier shapeVerifier =
                new NativeRegistrationChunkAssemblyShapeVerifier();
        for (int ordinal = 0; ordinal < plan.chunks().size(); ordinal++) {
            NativeRegistrationControlTopologyPlan.Chunk chunk = plan.chunks().get(ordinal);
            ArrayList<String> expectedCalls = new ArrayList<>(
                    chunk.owners().stream()
                            .map(NativeRegistrationControlTopologyPlan.Owner::symbol)
                            .toList());
            boolean forwards = ordinal + 1 < plan.chunks().size();
            String nextChunk = null;
            if (forwards) {
                nextChunk = plan.chunks().get(ordinal + 1).symbol();
                expectedCalls.add(nextChunk);
            }
            NativeRegistrationAssemblyInstructionSet.ContinuationProfile profile =
                    verifier.verifyStrict(
                            chunk.symbol(),
                            expectedCalls,
                            true,
                            false,
                            nextChunk == null ? Set.of() : Set.of(nextChunk));
            entryFlow.verifyChunk(
                    chunk.symbol(),
                    chunk.owners().stream()
                            .map(NativeRegistrationControlTopologyPlan.Owner::symbol)
                            .toList(),
                    nextChunk);
            if (forwards) {
                shapeVerifier.verify(chunk.postCallVariant(), profile, chunk.symbol());
                requireDistinct(continuationShapes, profile.signature(), chunk.symbol());
            }
        }
    }

    private EvidenceBinding evidence(
            ZigBuildWorkspace workspace,
            ZigBuildProgressPlan.TargetPlan targetPlan,
            Path registrationSource) throws IOException {
        ArrayList<Path> result = new ArrayList<>();
        Path registrationEvidence = null;
        for (ZigBuildProgressPlan.CompileUnit unit : targetPlan.compileUnits()) {
            for (ZigBuildProgressPlan.CompileInput input : unit.inputs()) {
                if (input.kind() == ZigBuildProgressPlan.CompileInputKind.C) {
                    Path evidence = ZigOptimizedAssemblyEvidence.path(
                            workspace,
                            targetPlan.target(),
                            input);
                    result.add(evidence);
                    if (input.source().toAbsolutePath().normalize().equals(
                            registrationSource.toAbsolutePath().normalize())) {
                        if (registrationEvidence != null) {
                            throw NativeRegistrationAssemblyIndex.failure(
                                    "REGISTRATION_ASSEMBLY_EVIDENCE_BINDING",
                                    registrationSource.toString());
                        }
                        registrationEvidence = evidence;
                    }
                }
            }
        }
        if (registrationEvidence == null) {
            throw NativeRegistrationAssemblyIndex.failure(
                    "REGISTRATION_ASSEMBLY_EVIDENCE_BINDING",
                    registrationSource.toString());
        }
        return new EvidenceBinding(List.copyOf(result), registrationEvidence);
    }

    private Set<String> coreSymbols(NativeRegistrationControlTopologyPlan plan) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add(ROOT);
        result.add(plan.aggregateSymbol());
        plan.routePlan().routes().forEach(route -> result.add(route.symbol()));
        plan.chunks().forEach(chunk -> result.add(chunk.symbol()));
        plan.owners().forEach(owner -> result.add(owner.symbol()));
        return Set.copyOf(result);
    }

    private Set<String> bindingSymbols(
            NativeRegistrationControlTopologyPlan plan,
            Set<String> coreSymbols) {
        if (plan.owners().isEmpty()) {
            return coreSymbols;
        }
        LinkedHashSet<String> result = new LinkedHashSet<>(coreSymbols);
        result.addAll(plan.failureSymbols().symbols());
        return Set.copyOf(result);
    }

    private void requireDistinct(
            Set<List<String>> observed,
            List<String> signature,
            String symbol) throws IOException {
        if (signature.isEmpty() || !observed.add(List.copyOf(signature))) {
            throw NativeRegistrationAssemblyIndex.failure(
                    "NON_DISTINCT_POST_CALL_TOPOLOGY",
                    symbol);
        }
    }

    private record EvidenceBinding(
            List<Path> allEvidence,
            Path registrationEvidence) {}
}
