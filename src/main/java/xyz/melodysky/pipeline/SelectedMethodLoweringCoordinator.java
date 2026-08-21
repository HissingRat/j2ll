package xyz.melodysky.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.cfg.MethodCfgResult;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.pass.ActiveUseCarrierFusionPass;
import xyz.melodysky.ir.pass.JdkPureNativeIntrinsicPipeline;
import xyz.melodysky.ir.pass.OptimizationPipeline;
import xyz.melodysky.ir.pass.PassContext;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.toolchain.initializer.InitializerImplementationPlan;
import xyz.melodysky.toolchain.initializer.InitializerImplementationPlanner;

/** Owns selected-method CFG, SSA and ordinary optimization preparation. */
final class SelectedMethodLoweringCoordinator {
    private final MethodCfgBuilder cfgBuilder = new MethodCfgBuilder();
    private final OptimizationPipeline optimizationPipeline =
            OptimizationPipeline.defaultPipeline();
    private final JdkPureNativeIntrinsicPipeline intrinsicPipeline =
            new JdkPureNativeIntrinsicPipeline();
    private final MethodRewritePlanner rewritePlanner;

    SelectedMethodLoweringCoordinator(MethodRewritePlanner rewritePlanner) {
        this.rewritePlanner = java.util.Objects.requireNonNull(
                rewritePlanner,
                "rewritePlanner");
    }

    Result run(
            ParsedProgram program,
            List<ParsedMethod> selectedMethods,
            Set<String> versionedClassNames,
            BytecodeToSsaLowerer ssaLowerer,
            InitializerImplementationPlanner initializerPlanner,
            long wrapperSymbolSeed,
            MainlineProgress progress) {
        java.util.Objects.requireNonNull(program, "program");
        selectedMethods = List.copyOf(java.util.Objects.requireNonNull(
                selectedMethods,
                "selectedMethods"));
        versionedClassNames = Set.copyOf(java.util.Objects.requireNonNull(
                versionedClassNames,
                "versionedClassNames"));
        java.util.Objects.requireNonNull(ssaLowerer, "ssaLowerer");
        java.util.Objects.requireNonNull(initializerPlanner, "initializerPlanner");
        java.util.Objects.requireNonNull(progress, "progress");

        LinkedHashMap<String, MethodCfgResult> cfgByMethod = new LinkedHashMap<>();
        ArrayList<SsaMethodResult> ssaResults = new ArrayList<>();
        LinkedHashMap<String, IrMethod> rawIr = new LinkedHashMap<>();
        LinkedHashMap<String, IrMethod> optimizedIr = new LinkedHashMap<>();
        LinkedHashMap<String, InitializerImplementationPlan> initializerPlans =
                new LinkedHashMap<>();
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, ParsedClass> classesByName = program.classes().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ParsedClass::internalName,
                        parsedClass -> parsedClass,
                        (left, right) -> left,
                        LinkedHashMap::new));

        progress.methodLowering(selectedMethods.size());
        int methodIndex = 0;
        for (ParsedMethod method : selectedMethods) {
            progress.methodLoweringProgress(
                    ++methodIndex,
                    selectedMethods.size(),
                    method.methodKey());
            if (versionedClassNames.contains(method.owner())) {
                addVersionedSkip(method, ssaResults, diagnostics);
                continue;
            }
            var cfgResult = cfgBuilder.build(method);
            diagnostics.addAll(cfgResult.diagnostics());
            if (cfgResult.artifact().isEmpty()) {
                continue;
            }
            MethodCfgResult cfg = cfgResult.artifact().orElseThrow();
            cfgByMethod.put(method.methodKey(), cfg);
            var lowering = ssaLowerer.lower(cfg);
            diagnostics.addAll(lowering.diagnostics());
            if (lowering.artifact().isEmpty()) {
                continue;
            }
            SsaMethodResult ssa = lowering.artifact().orElseThrow();
            ssaResults.add(ssa);
            if (ssa.irMethod().isEmpty()) {
                continue;
            }
            IrMethod raw = ssa.irMethod().orElseThrow();
            rawIr.put(method.methodKey(), raw);
            IrMethod optimizationInput = initializerBody(
                    method,
                    raw,
                    classesByName,
                    initializerPlanner,
                    initializerPlans,
                    wrapperSymbolSeed);
            var optimizedResult = optimizationPipeline.run(
                    optimizationInput,
                    PassContext.empty());
            diagnostics.addAll(optimizedResult.diagnostics());
            IrMethod optimized = optimizedResult.artifact().orElse(optimizationInput);
            var intrinsicResult = intrinsicPipeline.run(optimized);
            diagnostics.addAll(intrinsicResult.diagnostics());
            optimizedIr.put(
                    method.methodKey(),
                    intrinsicResult.artifact().orElse(optimized));
        }
        fuseActiveUses(optimizedIr, diagnostics);
        progress.methodLoweringComplete(selectedMethods.size());
        return new Result(
                cfgByMethod,
                ssaResults,
                rawIr,
                optimizedIr,
                initializerPlans,
                diagnostics);
    }

    private IrMethod initializerBody(
            ParsedMethod method,
            IrMethod raw,
            Map<String, ParsedClass> classesByName,
            InitializerImplementationPlanner initializerPlanner,
            Map<String, InitializerImplementationPlan> initializerPlans,
            long wrapperSymbolSeed) {
        if (!method.name().equals("<init>") && !method.name().equals("<clinit>")) {
            return raw;
        }
        ParsedClass ownerClass = classesByName.get(method.owner());
        if (ownerClass == null) {
            return raw;
        }
        MethodRewriteDecision decision = rewritePlanner.planMethod(
                ownerClass,
                method,
                wrapperSymbolSeed);
        Optional<InitializerImplementationPlan> plan = initializerPlanner.plan(
                decision,
                raw);
        plan.ifPresent(value -> initializerPlans.put(method.methodKey(), value));
        return plan.map(InitializerImplementationPlan::nativeBody).orElse(raw);
    }

    private void fuseActiveUses(
            LinkedHashMap<String, IrMethod> optimizedIr,
            List<Diagnostic> diagnostics) {
        OptimizationPipeline pipeline = new OptimizationPipeline(List.of(
                new ActiveUseCarrierFusionPass(Set.copyOf(optimizedIr.keySet()))));
        LinkedHashMap<String, IrMethod> fused = new LinkedHashMap<>();
        optimizedIr.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    var result = pipeline.run(entry.getValue(), PassContext.empty());
                    diagnostics.addAll(result.diagnostics());
                    fused.put(entry.getKey(), result.artifact().orElse(entry.getValue()));
                });
        optimizedIr.clear();
        optimizedIr.putAll(fused);
    }

    private void addVersionedSkip(
            ParsedMethod method,
            List<SsaMethodResult> ssaResults,
            List<Diagnostic> diagnostics) {
        String reason = "base class has a META-INF/versions counterpart; "
                + "preserving the original method avoids registering a native "
                + "binding against a runtime-selected versioned class";
        ssaResults.add(SsaMethodResult.skipped(
                method,
                DiagnosticStage.LOWERING,
                "MULTI_RELEASE_VERSIONED_CLASS",
                reason));
        diagnostics.add(Diagnostic.warning(
                        DiagnosticStage.LOWERING,
                        DiagnosticCode.of("MULTI_RELEASE_VERSIONED_CLASS"),
                        reason)
                .at(DiagnosticLocation.methodLocation(
                        method.owner(),
                        method.name(),
                        method.descriptor()))
                .withDecision(LoweringStatus.SKIPPED.wireName()));
    }

    record Result(
            Map<String, MethodCfgResult> cfgByMethod,
            List<SsaMethodResult> ssaResults,
            Map<String, IrMethod> rawIr,
            Map<String, IrMethod> optimizedIr,
            Map<String, InitializerImplementationPlan> initializerPlans,
            List<Diagnostic> diagnostics) {
        Result {
            cfgByMethod = immutableMap(cfgByMethod);
            ssaResults = List.copyOf(ssaResults);
            rawIr = immutableMap(rawIr);
            optimizedIr = immutableMap(optimizedIr);
            initializerPlans = immutableMap(initializerPlans);
            diagnostics = List.copyOf(diagnostics);
        }

        private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }
}
