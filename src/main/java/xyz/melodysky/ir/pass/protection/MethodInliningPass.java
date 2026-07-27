package xyz.melodysky.ir.pass.protection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import xyz.melodysky.diagnostic.DiagnosticSeverity;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrProgram;
import xyz.melodysky.ir.validate.IrMethodValidator;

/**
 * First-stage program-level SSA method inlining.
 *
 * <p>This pass consumes analysis-approved direct edges instead of attempting
 * Java call resolution inside the IR layer. It deliberately keeps the
 * original callee in the program so reflection and native registration
 * surfaces are unchanged.
 */
public final class MethodInliningPass {
    private final IrMethodValidator validator = new IrMethodValidator();
    private final MethodInliningSafety safety = new MethodInliningSafety();
    private final MethodInliningRewriter rewriter = new MethodInliningRewriter();

    public MethodInliningResult run(
            IrProgram program,
            MethodInliningPlan plan,
            MethodInliningOptions options) {
        if (!options.enabled()) {
            List<MethodInliningDecision> disabled = plan.candidates().stream()
                    .map(candidate -> decision(
                            candidate,
                            "<disabled>",
                            MethodInliningDecision.Status.SKIPPED,
                            MethodInliningReason.DISABLED))
                    .toList();
            return new MethodInliningResult(program, disabled);
        }

        Map<String, IrMethod> originalMethods = indexMethods(program);
        MethodInliningCallGraph callGraph = new MethodInliningCallGraph(program);
        ArrayList<MethodInliningDecision> decisions = new ArrayList<>();
        HashSet<MethodInliningCandidate> seenCandidates = new HashSet<>();
        ArrayList<IrClass> classes = new ArrayList<>();

        for (IrClass irClass : program.classes()) {
            ArrayList<IrMethod> methods = new ArrayList<>();
            for (IrMethod method : irClass.methods()) {
                List<MethodInliningSite> sites = collectSites(method, plan);
                sites.forEach(site -> seenCandidates.add(site.candidate()));
                methods.add(inlineSites(
                        method,
                        sites,
                        originalMethods,
                        callGraph,
                        options,
                        decisions));
            }
            classes.add(new IrClass(irClass.internalName(), methods));
        }
        for (MethodInliningCandidate candidate : plan.candidates()) {
            if (!seenCandidates.contains(candidate)) {
                decisions.add(decision(
                        candidate,
                        "<none>",
                        MethodInliningDecision.Status.SKIPPED,
                        MethodInliningReason.NO_CANDIDATE));
            }
        }
        return new MethodInliningResult(new IrProgram(classes), decisions);
    }

    private IrMethod inlineSites(
            IrMethod originalCaller,
            List<MethodInliningSite> sites,
            Map<String, IrMethod> originalMethods,
            MethodInliningCallGraph callGraph,
            MethodInliningOptions options,
            List<MethodInliningDecision> decisions) {
        if (sites.isEmpty()) {
            return originalCaller;
        }
        if (hasValidationError(originalCaller)) {
            sites.forEach(site -> decisions.add(decision(
                    site,
                    MethodInliningDecision.Status.SKIPPED,
                    MethodInliningReason.VALIDATION_FAILED)));
            return originalCaller;
        }

        List<MethodInliningSite> selected = sites.stream()
                .sorted(siteOrder())
                .limit(options.maxSitesPerCaller())
                .toList();
        sites.stream()
                .sorted(siteOrder())
                .skip(options.maxSitesPerCaller())
                .forEach(site -> decisions.add(decision(
                        site,
                        MethodInliningDecision.Status.SKIPPED,
                        MethodInliningReason.SITE_LIMIT)));

        IrMethod current = originalCaller;
        List<MethodInliningSite> rewriteOrder = selected.stream()
                .sorted(siteOrder().reversed())
                .toList();
        for (MethodInliningSite site : rewriteOrder) {
            MethodInliningCandidate candidate = site.candidate();
            IrMethod callee = originalMethods.get(candidate.calleeMethodKey());
            String rejection = candidateRejection(candidate, callee, callGraph, options);
            if (rejection != null) {
                decisions.add(decision(site, MethodInliningDecision.Status.SKIPPED, rejection));
                continue;
            }

            MethodInliningRewriteResult rewrite = rewriter.inline(current, callee, site, options.seed());
            if (rewrite.method().isEmpty()) {
                decisions.add(decision(
                        site,
                        MethodInliningDecision.Status.SKIPPED,
                        rewrite.reasonCode()));
                continue;
            }
            IrMethod candidateMethod = rewrite.method().orElseThrow();
            if (hasValidationError(candidateMethod)) {
                decisions.add(decision(
                        site,
                        MethodInliningDecision.Status.FAILED,
                        MethodInliningReason.VALIDATION_FAILED));
                continue;
            }
            current = candidateMethod;
            decisions.add(decision(
                    site,
                    MethodInliningDecision.Status.INLINED,
                    MethodInliningReason.INLINED));
        }
        return current;
    }

    private String candidateRejection(
            MethodInliningCandidate candidate,
            IrMethod callee,
            MethodInliningCallGraph callGraph,
            MethodInliningOptions options) {
        if (callee == null) {
            return MethodInliningReason.NO_CANDIDATE;
        }
        if (!candidate.singleTarget()) {
            return MethodInliningReason.NOT_SINGLE_TARGET;
        }
        if (!candidate.callerUsesFinalNativePath() || !candidate.calleeUsesFinalNativePath()) {
            return MethodInliningReason.NON_NATIVE_PATH;
        }
        if (candidate.reflectionSensitive()) {
            return MethodInliningReason.REFLECTION_SENSITIVE;
        }
        if (callGraph.isRecursiveEdge(candidate.callerMethodKey(), candidate.calleeMethodKey())) {
            return MethodInliningReason.RECURSIVE;
        }
        if (hasValidationError(callee)) {
            return MethodInliningReason.VALIDATION_FAILED;
        }
        return safety.rejectionReason(callee, options.maxCalleeInstructions()).orElse(null);
    }

    private List<MethodInliningSite> collectSites(
            IrMethod caller,
            MethodInliningPlan plan) {
        ArrayList<MethodInliningSite> sites = new ArrayList<>();
        for (int blockIndex = 0; blockIndex < caller.blocks().size(); blockIndex++) {
            var block = caller.blocks().get(blockIndex);
            for (int instructionIndex = 0; instructionIndex < block.instructions().size(); instructionIndex++) {
                var instruction = block.instructions().get(instructionIndex);
                if (instruction.symbol().isEmpty()) {
                    continue;
                }
                var candidate = plan.candidate(
                        caller.methodKey(),
                        instruction.symbol().orElseThrow(),
                        instruction.opcode());
                if (candidate.isPresent()) {
                    sites.add(new MethodInliningSite(
                            blockIndex,
                            block.name(),
                            instructionIndex,
                            candidate.orElseThrow()));
                }
            }
        }
        return List.copyOf(sites);
    }

    private Map<String, IrMethod> indexMethods(IrProgram program) {
        HashMap<String, IrMethod> methods = new HashMap<>();
        for (IrClass irClass : program.classes()) {
            for (IrMethod method : irClass.methods()) {
                IrMethod previous = methods.putIfAbsent(method.methodKey(), method);
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate IR method key: " + method.methodKey());
                }
            }
        }
        return Map.copyOf(methods);
    }

    private boolean hasValidationError(IrMethod method) {
        return validator.validate(method).stream()
                .anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR);
    }

    private Comparator<MethodInliningSite> siteOrder() {
        return Comparator.comparingInt(MethodInliningSite::blockIndex)
                .thenComparingInt(MethodInliningSite::instructionIndex);
    }

    private MethodInliningDecision decision(
            MethodInliningSite site,
            MethodInliningDecision.Status status,
            String reasonCode) {
        return decision(site.candidate(), site.displayName(), status, reasonCode);
    }

    private MethodInliningDecision decision(
            MethodInliningCandidate candidate,
            String callSite,
            MethodInliningDecision.Status status,
            String reasonCode) {
        return new MethodInliningDecision(
                candidate.callerMethodKey(),
                candidate.calleeMethodKey(),
                callSite,
                status,
                reasonCode);
    }
}
