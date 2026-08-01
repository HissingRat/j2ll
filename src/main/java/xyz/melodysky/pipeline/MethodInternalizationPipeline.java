package xyz.melodysky.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.analysis.method.NativeMethodInternalizationDecision;
import xyz.melodysky.analysis.method.NativeMethodInternalizationPlan;
import xyz.melodysky.analysis.method.NativeMethodInternalizationPlanner;
import xyz.melodysky.analysis.method.PublicMethodInternalizationAllowListResolver;
import xyz.melodysky.analysis.method.PublicMethodInternalizationDiagnostics;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.analysis.world.WholeProgramAnalysisDiagnostics;
import xyz.melodysky.analysis.world.WholeProgramAnalysisFeature;
import xyz.melodysky.analysis.world.WholeProgramAnalysisPolicy;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.report.ProtectionPassReport;
import xyz.melodysky.protection.audit.ProtectionApplicability;
import xyz.melodysky.toolchain.NativeImplementationPlan;

/** Final-plan method-removal analysis; it never changes Java call semantics. */
public final class MethodInternalizationPipeline {
    public MethodInternalizationPipelineResult run(
            ResolvedConfig config,
            ParsedProgram inputProgram,
            ClassHierarchy hierarchy,
            CallGraph callGraph,
            ReflectionPlan reflectionPlan,
            Set<String> versionedClassNames,
            NativeImplementationPlan implementationPlan,
            WholeProgramAnalysisPolicy wholeProgramPolicy,
            long evidenceSeed) {
        boolean enabled = config.protection().enabled()
                && config.protection().ir().enabled()
                && config.protection().ir()
                        .methodInternalization();
        if (!enabled) {
            NativeMethodInternalizationPlan plan =
                    NativeMethodInternalizationPlan.disabled();
            return new MethodInternalizationPipelineResult(
                    plan,
                    report(plan, evidenceSeed),
                    List.of(),
                    WholeProgramAnalysisScope.NOT_REQUIRED,
                    false);
        }
        WholeProgramAnalysisScope scope = wholeProgramPolicy.scopeFor(
                WholeProgramAnalysisFeature.METHOD_INTERNALIZATION,
                config.worldModel());
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        PublicMethodInternalizationAllowListResolver.Result publicAllowList =
                new PublicMethodInternalizationAllowListResolver().resolve(
                        inputProgram,
                        config.protection().ir()
                                .publicMethodInternalizationAllowList());
        diagnostics.addAll(publicAllowList.diagnostics());
        WholeProgramAnalysisScope effectiveScope = scope;
        ParsedProgram analysisProgram = inputProgram;
        ClassHierarchy analysisHierarchy = hierarchy;
        CallGraph analysisCallGraph = callGraph;
        ReflectionPlan analysisReflectionPlan = reflectionPlan;
        boolean analyzedClasspath = false;
        if (!publicAllowList.successful()) {
            effectiveScope = WholeProgramAnalysisScope.UNAVAILABLE;
        } else if (scope.analyzesClasspath()) {
            analyzedClasspath = true;
            MethodInternalizationAnalysisWorldBuilder.Result worldResult =
                    new MethodInternalizationAnalysisWorldBuilder().build(
                            inputProgram,
                            config.classPath());
            diagnostics.addAll(worldResult.diagnostics());
            if (worldResult.complete()) {
                MethodInternalizationAnalysisWorld world =
                        worldResult.world().orElseThrow();
                analysisProgram = world.combinedProgram();
                analysisHierarchy = world.hierarchy();
                analysisCallGraph = world.callGraph();
                analysisReflectionPlan = world.reflectionPlan();
            } else {
                effectiveScope = WholeProgramAnalysisScope.UNAVAILABLE;
            }
        }
        NativeMethodInternalizationPlan plan =
                new NativeMethodInternalizationPlanner().plan(
                        true,
                        effectiveScope,
                        analysisProgram,
                        analysisHierarchy,
                        analysisCallGraph,
                        analysisReflectionPlan,
                        versionedClassNames,
                        implementationPlan,
                        publicAllowList.methods());
        addAcceptedPublicReflectionRiskDiagnostic(
                plan,
                analysisReflectionPlan,
                diagnostics);
        if (scope
                == WholeProgramAnalysisScope
                        .CURRENT_JAR_ONLY_USER_APPROVED) {
            diagnostics.add(Diagnostic.warning(
                            DiagnosticStage.PROTECTION,
                            WholeProgramAnalysisDiagnostics
                                    .CURRENT_JAR_ONLY_USER_APPROVED,
                            "methodInternalization is using user-approved "
                                    + "current-input-JAR-only reference analysis; "
                                    + "classPath and external reflection/JNI/agent "
                                    + "observers and external subclasses were not analyzed")
                    .withDecision(scope.wireName()));
        }
        return new MethodInternalizationPipelineResult(
                plan,
                report(plan, evidenceSeed),
                diagnostics,
                effectiveScope,
                analyzedClasspath);
    }

    private void addAcceptedPublicReflectionRiskDiagnostic(
            NativeMethodInternalizationPlan plan,
            ReflectionPlan reflectionPlan,
            List<Diagnostic> diagnostics) {
        long acceptedPublicMethods = plan.decisions().stream()
                .filter(NativeMethodInternalizationDecision::internalized)
                .filter(decision -> decision.access().equals("public"))
                .count();
        if (acceptedPublicMethods == 0
                || reflectionPlan.unsupportedSites().isEmpty()) {
            return;
        }
        diagnostics.add(Diagnostic.warning(
                        DiagnosticStage.PROTECTION,
                        PublicMethodInternalizationDiagnostics
                                .UNRESOLVED_REFLECTION_RISK_ACCEPTED,
                        "exact public-method allowlist accepted removal of "
                                + acceptedPublicMethods
                                + " method(s) while "
                                + reflectionPlan.unsupportedSites().size()
                                + " unresolved reflection site(s) remain; those sites "
                                + "were not treated as evidence that the exact methods "
                                + "are observed")
                .withDecision("userAccepted"));
    }

    private ProtectionPassReport report(
            NativeMethodInternalizationPlan plan,
            long evidenceSeed) {
        if (!plan.enabled()) {
            return new ProtectionPassReport(
                    "METHOD_INTERNALIZATION",
                    "FINAL_NATIVE_PLAN",
                    "SKIPPED",
                    "PROTECTION_PASS_DISABLED",
                    List.of(),
                    List.of(),
                    Long.toString(evidenceSeed),
                    List.of(),
                    List.of());
        }
        List<NativeMethodInternalizationDecision> internalized =
                plan.decisions().stream()
                        .filter(NativeMethodInternalizationDecision
                                ::internalized)
                        .toList();
        if (internalized.isEmpty()) {
            String reason = plan.decisions().stream()
                    .flatMap(decision -> decision.reasons().stream())
                    .map(Enum::name)
                    .sorted()
                    .findFirst()
                    .orElse(
                            "METHOD_INTERNALIZATION_NO_CANDIDATE");
            return new ProtectionPassReport(
                    "METHOD_INTERNALIZATION",
                    "FINAL_NATIVE_PLAN",
                    "SKIPPED",
                    reason,
                    List.of(),
                    List.of(),
                    Long.toString(evidenceSeed),
                    List.of(),
                    coverage(plan));
        }
        return new ProtectionPassReport(
                "METHOD_INTERNALIZATION",
                "FINAL_NATIVE_PLAN",
                "RAN",
                "METHOD_INTERNALIZATION",
                internalized.stream()
                        .map(decision ->
                                decision.method().methodKey())
                        .toList(),
                List.of(),
                Long.toString(evidenceSeed),
                List.of(),
                coverage(plan));
    }

    private List<xyz.melodysky.protection.audit
                    .ProtectionPassCoverageFact>
            coverage(NativeMethodInternalizationPlan plan) {
        return plan.decisions().stream()
                .map(decision -> {
                    boolean affected = decision.internalized();
                    String reason = decision.reasons()
                            .get(0)
                            .reasonCode();
                    return ProtectionCoverageFacts.method(
                            "FINAL_NATIVE_PLAN",
                            "METHOD_INTERNALIZATION",
                            decision.method().methodKey(),
                            true,
                            affected
                                    ? ProtectionApplicability
                                            .APPLICABLE
                                    : ProtectionApplicability
                                            .NOT_APPLICABLE,
                            affected,
                            affected ? "RAN" : "SKIPPED",
                            reason);
                })
                .toList();
    }

}
