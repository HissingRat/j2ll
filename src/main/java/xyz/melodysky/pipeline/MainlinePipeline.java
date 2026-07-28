package xyz.melodysky.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.CallGraphBuilder;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.analysis.hierarchy.ClassHierarchyStage;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.analysis.reflection.StaticReflectionResolver;
import xyz.melodysky.analysis.runtime.RuntimeAnalysisPipeline;
import xyz.melodysky.analysis.runtime.RuntimeTypeResult;
import xyz.melodysky.analysis.world.WholeProgramAnalysisFeature;
import xyz.melodysky.analysis.world.WholeProgramAnalysisPolicy;
import xyz.melodysky.analysis.world.WholeProgramAnalysisRequirements;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.backend.llvm.protection.LlvmBlockLayoutPerturbationResult;
import xyz.melodysky.backend.llvm.protection.LlvmCallIndirectionResult;
import xyz.melodysky.backend.llvm.protection.LlvmGlobalLayoutResult;
import xyz.melodysky.backend.llvm.protection.LlvmIrCallIndirectionResult;
import xyz.melodysky.backend.llvm.protection.LlvmOpaquePredicateResult;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.config.SelectorMatchResult;
import xyz.melodysky.config.SelectorMatcher;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticBag;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.cfg.MethodCfgResult;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassParseResult;
import xyz.melodysky.frontend.classfile.ClassParseStage;
import xyz.melodysky.frontend.classfile.JarClassFileSource;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.ir.model.BusinessStringSymbolMapper;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.pass.OptimizationPipeline;
import xyz.melodysky.ir.pass.PassContext;
import xyz.melodysky.ir.pass.protection.ProtectionPipeline;
import xyz.melodysky.ir.pass.protection.ProtectionAvailabilityReporter;
import xyz.melodysky.ir.pass.protection.StringEncryptionPass;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.packaging.ClassRewriteResult;
import xyz.melodysky.packaging.EmbeddedLibraryLayout;
import xyz.melodysky.packaging.JarPreservationReport;
import xyz.melodysky.packaging.JarRepackager;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.NativeLoaderClassGenerator;
import xyz.melodysky.packaging.NativeOriginalClassRewriter;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.packaging.RuntimeLoaderCollisionValidator;
import xyz.melodysky.packaging.SignatureActionReport;
import xyz.melodysky.packaging.JarSignatureResigner;
import xyz.melodysky.packaging.JarSignatureResignResult;
import xyz.melodysky.packaging.J2llMetadataEntries;
import xyz.melodysky.packaging.InternalizedFieldClassTransform;
import xyz.melodysky.packaging.SignatureResignPreflight;
import xyz.melodysky.packaging.SignatureResignPreflightResult;
import xyz.melodysky.progress.BuildProgressListener;
import xyz.melodysky.protection.BuildProtectionIdentity;
import xyz.melodysky.protection.BuildProtectionMaterials;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.report.EmbeddedLibraryReport;
import xyz.melodysky.report.ArtifactAudit;
import xyz.melodysky.report.ArtifactAuditResult;
import xyz.melodysky.report.ArtifactAuditReportWriter;
import xyz.melodysky.report.FailureReportWriter;
import xyz.melodysky.report.FieldInternalizationReportWriter;
import xyz.melodysky.report.SkippedMethodReportWriter;
import xyz.melodysky.report.KnownBlockersWriter;
import xyz.melodysky.report.LoweringReportAssembler;
import xyz.melodysky.report.OpcodeSupportMatrixWriter;
import xyz.melodysky.report.PackagingReportWriter;
import xyz.melodysky.report.ProtectionPassReport;
import xyz.melodysky.report.ProtectionReportWriter;
import xyz.melodysky.report.ReleaseReadinessGate;
import xyz.melodysky.report.ReleaseReadinessWriter;
import xyz.melodysky.report.ReportIndexWriter;
import xyz.melodysky.report.ReportJsonWriter;
import xyz.melodysky.report.ResolvedConfigReportWriter;
import xyz.melodysky.report.SensitivePlaintextFact;
import xyz.melodysky.report.SummaryMarkdownWriter;
import xyz.melodysky.report.SummaryReportWriter;
import xyz.melodysky.report.SymbolAuditReportWriter;
import xyz.melodysky.report.SupportMatrixWriter;
import xyz.melodysky.runtime.metadata.RuntimeMetadataDumpWriter;
import xyz.melodysky.runtime.metadata.RuntimeMetadataIndex;
import xyz.melodysky.runtime.metadata.RuntimeMetadataIndexBuilder;
import xyz.melodysky.toolchain.ClassArtifact;
import xyz.melodysky.toolchain.ClassArtifactInput;
import xyz.melodysky.toolchain.IntermediateArtifactIndexWriter;
import xyz.melodysky.toolchain.IntermediateArtifactLayout;
import xyz.melodysky.toolchain.IntermediateArtifactLayoutPlanner;
import xyz.melodysky.toolchain.MethodArtifact;
import xyz.melodysky.toolchain.MethodArtifactInput;
import xyz.melodysky.toolchain.NativeBuildPlan;
import xyz.melodysky.toolchain.NativeBuildPlanner;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeImplementationPlanner;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeMethodImplementation;
import xyz.melodysky.toolchain.initializer.InitializerImplementationPlan;
import xyz.melodysky.toolchain.initializer.InitializerImplementationPlanner;
import xyz.melodysky.toolchain.NativeLibraryArtifact;
import xyz.melodysky.toolchain.NativeLibraryName;
import xyz.melodysky.toolchain.NativeLlvmCompilation;
import xyz.melodysky.toolchain.NativeLlvmCompiler;
import xyz.melodysky.toolchain.NativeLlvmModuleCompilation;
import xyz.melodysky.toolchain.ZigNativeBuildResult;
import xyz.melodysky.toolchain.ZigNativeLibraryBuilder;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.TargetTriple;
import xyz.melodysky.toolchain.ToolchainDiagnostics;
import xyz.melodysky.toolchain.symbols.ExportList;
import xyz.melodysky.toolchain.symbols.SymbolAudit;
import xyz.melodysky.toolchain.symbols.SymbolAuditResult;
import xyz.melodysky.toolchain.symbols.SymbolVisibilityPlanner;

public final class MainlinePipeline {
    private final AsmClassParser parser = new AsmClassParser();
    private final SelectorMatcher selectorMatcher = new SelectorMatcher();
    private final MethodCfgBuilder cfgBuilder = new MethodCfgBuilder();
    private final OptimizationPipeline optimizationPipeline = OptimizationPipeline.defaultPipeline();
    private final ProtectionPipeline protectionPipeline = ProtectionPipeline.defaultPipeline();
    private final LlvmTextEmitter llvmEmitter = new LlvmTextEmitter();
    private final MethodRewritePlanner rewritePlanner = new MethodRewritePlanner();
    private final NativeOriginalClassRewriter classRewriter = new NativeOriginalClassRewriter();
    private final LoweringReportAssembler loweringReportAssembler = new LoweringReportAssembler();
    private final ProtectionEvidenceAssembler protectionEvidenceAssembler =
            new ProtectionEvidenceAssembler();
    private final java.util.function.Function<String, String> signatureEnvironment;
    private final ArtifactAudit artifactAudit;

    public MainlinePipeline() {
        this(System::getenv);
    }

    public MainlinePipeline(java.util.function.Function<String, String> signatureEnvironment) {
        this(signatureEnvironment, new ArtifactAudit());
    }

    public MainlinePipeline(
            java.util.function.Function<String, String> signatureEnvironment,
            ArtifactAudit artifactAudit) {
        this.signatureEnvironment = java.util.Objects.requireNonNull(signatureEnvironment, "signatureEnvironment");
        this.artifactAudit = java.util.Objects.requireNonNull(artifactAudit, "artifactAudit");
    }

    public MainlinePipelineResult run(ResolvedConfig config, Path workspaceRoot) throws IOException {
        return run(config, workspaceRoot, BuildProgressListener.none());
    }

    public MainlinePipelineResult run(
            ResolvedConfig config,
            Path workspaceRoot,
            BuildProgressListener progress) throws IOException {
        return run(
                config,
                workspaceRoot,
                progress,
                WholeProgramAnalysisPolicy.strict());
    }

    public MainlinePipelineResult run(
            ResolvedConfig config,
            Path workspaceRoot,
            BuildProgressListener progress,
            WholeProgramAnalysisPolicy wholeProgramPolicy) throws IOException {
        return run(
                config,
                workspaceRoot,
                progress,
                wholeProgramPolicy,
                SkippedMethodApproval.rejectAll());
    }

    public MainlinePipelineResult run(
            ResolvedConfig config,
            Path workspaceRoot,
            BuildProgressListener progress,
            WholeProgramAnalysisPolicy wholeProgramPolicy,
            SkippedMethodApproval skippedMethodApproval) throws IOException {
        java.util.Objects.requireNonNull(
                skippedMethodApproval,
                "skippedMethodApproval");
        MainlineProgress buildProgress = new MainlineProgress(progress);
        buildProgress.inputInspection(config.jarFile());
        DiagnosticBag diagnostics = new DiagnosticBag();
        WorkspaceLayout workspaceLayout = new WorkspaceLayout(workspaceRoot);
        workspaceLayout.createDirectories();
        for (var requirement :
                new WholeProgramAnalysisRequirements().unmet(config, wholeProgramPolicy)) {
            diagnostics.add(Diagnostic.error(
                            DiagnosticStage.CONFIG,
                            requirement.diagnosticCode(),
                            requirement.feature().displayName()
                                    + " requires CLOSED_WORLD or an explicit current-JAR-only approval")
                    .withDecision("confirmationRequired"));
        }
        if (diagnostics.hasErrors()) {
            return failed(config, workspaceRoot, diagnostics, wholeProgramPolicy);
        }
        JarRepackager repackager = new JarRepackager();
        JarPreservationReport preservationReport = repackager.inspectPreservation(config.jarFile());
        SignatureActionReport signatureAction = repackager.inspectSignature(config.jarFile(), config.signaturePolicy());
        if (signatureAction.signedInput() && signatureAction.action().equals("fail")) {
            diagnostics.add(Diagnostic.error(
                            DiagnosticStage.PACKAGING,
                            xyz.melodysky.diagnostic.DiagnosticCode.SIGNED_INPUT_REJECTED,
                            signatureAction.reason())
                    .withDecision("failed"));
            return failed(
                    config,
                    workspaceRoot,
                    diagnostics,
                    preservationReport,
                    signatureAction,
                    wholeProgramPolicy);
        }
        if (signatureAction.signedInput() && signatureAction.action().equals("strip")) {
            diagnostics.add(Diagnostic.warning(
                            DiagnosticStage.PACKAGING,
                            xyz.melodysky.diagnostic.DiagnosticCode.SIGNATURE_STRIPPED,
                            signatureAction.reason())
                    .withDecision("strip"));
        }
        if (config.signaturePolicy() == xyz.melodysky.config.SignaturePolicy.RESIGN) {
            SignatureResignPreflightResult resignPreflight =
                    new SignatureResignPreflight(signatureEnvironment).validate(config.signing());
            if (!resignPreflight.successful()) {
                signatureAction = SignatureActionReport.resignFailed(
                        signatureAction.signedInput(),
                        signatureAction.removedEntries(),
                        resignPreflight.reasonCode(),
                        resignPreflight.reason());
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.PACKAGING,
                                xyz.melodysky.diagnostic.DiagnosticCode.SIGNATURE_RESIGN_FAILED,
                                resignPreflight.reason())
                        .withDecision("failed"));
                return failed(
                        config,
                        workspaceRoot,
                        diagnostics,
                        preservationReport,
                        signatureAction,
                        wholeProgramPolicy);
            }
        }
        RuntimeLoaderPlan loaderNamespacePlan =
                RuntimeLoaderPlan.create(config.embeddedLibraryDirectory());
        diagnostics.addAll(new RuntimeLoaderCollisionValidator().validate(
                config.jarFile(),
                loaderNamespacePlan));
        if (diagnostics.hasErrors()) {
            return failed(
                    config,
                    workspaceRoot,
                    diagnostics,
                    preservationReport,
                    signatureAction,
                    wholeProgramPolicy);
        }

        diagnostics.addAll(ProtectionAvailabilityReporter.currentImplementation().report(config.protection()));
        PipelineContext pipelineContext = PipelineContext.usingDiagnostics(diagnostics);
        buildProgress.classParsing(config.jarFile());
        PipelineRunResult parseRun = new CompilationPipeline(List.of(new ClassParseStage(parser)))
                .run(
                        new JarClassFileSource(config.jarFile()),
                        pipelineContext);
        if (parseRun.halted()) {
            return failed(config, workspaceRoot, diagnostics, wholeProgramPolicy);
        }
        ClassParseResult classParseResult = parseRun.artifactAs(ClassParseResult.class).orElseThrow();
        ParsedProgram program = classParseResult.program();

        buildProgress.methodSelection(program.classes().size());
        SelectorMatchResult selection = selectorMatcher.expand(program, config.whiteList(), config.blackList());
        Set<String> versionedClassNames =
                versionedClassNames(config.jarFile());
        diagnostics.addAll(selection.diagnostics());
        buildProgress.methodsSelected(selection.requestedMethods().size());

        buildProgress.programAnalysis(program.classes().size());
        PipelineRunResult hierarchyRun = new CompilationPipeline(List.of(new ClassHierarchyStage(config.worldModel())))
                .run(classParseResult, pipelineContext);
        if (hierarchyRun.halted()) {
            return failed(config, workspaceRoot, diagnostics, wholeProgramPolicy);
        }
        ClassHierarchy hierarchy = hierarchyRun
                .artifactAs(ClassHierarchy.class)
                .orElseThrow();

        var metadataResult = new RuntimeMetadataIndexBuilder().build(program);
        diagnostics.addAll(metadataResult.diagnostics());
        RuntimeMetadataIndex metadataIndex = metadataResult.artifact().orElseThrow();
        ReflectionPlan reflectionPlan = new StaticReflectionResolver().resolve(program, metadataIndex);
        CallGraph callGraph = new CallGraphBuilder().buildCha(program, hierarchy, metadataIndex);
        RuntimeTypeResult runtimeTypes = new RuntimeAnalysisPipeline().analyze(program);

        int requestedMethodCount = selection.requestedMethods().size();
        buildProgress.methodLowering(requestedMethodCount);
        Map<String, MethodCfgResult> cfgByMethod = new LinkedHashMap<>();
        ArrayList<SsaMethodResult> ssaResults = new ArrayList<>();
        Map<String, IrMethod> rawIr = new LinkedHashMap<>();
        Map<String, IrMethod> optimizedIr = new LinkedHashMap<>();
        Map<String, IrMethod> protectedIr = new LinkedHashMap<>();
        Map<String, InitializerImplementationPlan> initializerPlans = new LinkedHashMap<>();
        Map<String, ParsedClass> classesByName = program.classes().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ParsedClass::internalName,
                        parsedClass -> parsedClass,
                        (left, right) -> left,
                        LinkedHashMap::new));
        ArrayList<ProtectionPassReport> protectionReports = new ArrayList<>();
        BuildProtectionIdentity buildProtectionIdentity =
                BuildProtectionIdentity.from(config.protection());
        BuildProtectionMaterials protectionMaterials =
                BuildProtectionMaterials.derive(buildProtectionIdentity);
        long irProtectionSeed = protectionMaterials.irMethodSeed();
        long programProtectionSeed = protectionMaterials.irProgramSeed();
        long fieldInternalizationSeed = protectionMaterials.fieldSeed();
        long templateStringSeed =
                protectionMaterials.businessStringSeed();
        long methodTableSeed = protectionMaterials.methodTableSeed();
        long wrapperSymbolSeed = protectionMaterials.wrapperSeed();
        long llvmNameSeed = protectionMaterials.llvmSymbolSeed();
        long llvmProtectionSeed =
                protectionMaterials.llvmProtectionSeed();
        NativeTextBuildKey nativeTextBuildKey = NativeTextBuildKey.fromBytes(
                protectionMaterials.nativeTextKey());
        NativeTextBuildKey businessNativeTextBuildKey =
                NativeTextBuildKey.fromBytes(
                        protectionMaterials.businessNativeTextKey());
        NativeTextBuildKey registrationBuildKey = NativeTextBuildKey.fromBytes(
                protectionMaterials.registrationKey());
        BusinessStringSymbolMapper businessStringSymbols =
                BusinessStringSymbolMapper.fromBytes(
                        businessNativeTextBuildKey.bytes());
        RuntimeTokenMapper runtimeTokens =
                RuntimeTokenMapper.fromBytes(nativeTextBuildKey.bytes());
        BytecodeToSsaLowerer ssaLowerer =
                new BytecodeToSsaLowerer(runtimeTokens);
        InitializerImplementationPlanner initializerPlanner =
                new InitializerImplementationPlanner(runtimeTokens);
        boolean llvmNameObfuscationEnabled = config.protection().enabled()
                && config.protection().llvm().enabled()
                && config.protection().llvm().nameObfuscation();
        boolean llvmCallIndirectionEnabled = config.protection().enabled()
                && config.protection().llvm().enabled()
                && config.protection().llvm().indirectCalls();
        boolean llvmBlockLayoutPerturbationEnabled = config.protection().enabled()
                && config.protection().llvm().enabled()
                && config.protection().llvm().blockLayoutPerturbation();
        boolean llvmOpaquePredicatesEnabled = config.protection().enabled()
                && config.protection().llvm().enabled()
                && config.protection().llvm().opaquePredicates();
        boolean llvmGlobalLayoutEnabled = config.protection().enabled()
                && config.protection().llvm().enabled()
                && config.protection().llvm().globalLayout();
        LlvmNameMangler llvmNameMangler = llvmNameObfuscationEnabled
                ? LlvmNameMangler.obfuscating(llvmNameSeed)
                : new LlvmNameMangler();
        LlvmModuleLowerer llvmLowerer = new LlvmModuleLowerer(
                llvmNameMangler,
                businessStringSymbols,
                runtimeTokens);
        xyz.melodysky.backend.llvm.protection.LlvmProtectionConfig llvmProtectionConfig =
                xyz.melodysky.backend.llvm.protection.LlvmProtectionConfig.selected(
                        llvmProtectionSeed,
                        false,
                        llvmOpaquePredicatesEnabled,
                        llvmBlockLayoutPerturbationEnabled,
                        llvmCallIndirectionEnabled,
                        llvmGlobalLayoutEnabled);
        int methodIndex = 0;
        for (ParsedMethod method : selection.requestedMethods()) {
            methodIndex++;
            buildProgress.methodLoweringProgress(methodIndex, requestedMethodCount, method.methodKey());
            if (versionedClassNames.contains(method.owner())) {
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
                                xyz.melodysky.diagnostic.DiagnosticCode.of(
                                        "MULTI_RELEASE_VERSIONED_CLASS"),
                                reason)
                        .at(xyz.melodysky.diagnostic.DiagnosticLocation
                                .methodLocation(
                                        method.owner(),
                                        method.name(),
                                        method.descriptor()))
                        .withDecision(LoweringStatus.SKIPPED.wireName()));
                continue;
            }
            var cfgResult = cfgBuilder.build(method);
            diagnostics.addAll(cfgResult.diagnostics());
            cfgResult.artifact().ifPresent(result -> cfgByMethod.put(method.methodKey(), result));
            if (cfgResult.artifact().isEmpty()) {
                continue;
            }
            var ssaResult = ssaLowerer.lower(cfgResult.artifact().orElseThrow());
            diagnostics.addAll(ssaResult.diagnostics());
            SsaMethodResult ssa = ssaResult.artifact().orElseThrow();
            ssaResults.add(ssa);
            if (ssa.irMethod().isEmpty()) {
                continue;
            }
            IrMethod raw = ssa.irMethod().orElseThrow();
            rawIr.put(method.methodKey(), raw);
            IrMethod optimizationInput = raw;
            if (method.name().equals("<init>") || method.name().equals("<clinit>")) {
                ParsedClass ownerClass = classesByName.get(method.owner());
                if (ownerClass != null) {
                    MethodRewriteDecision initializerDecision =
                            rewritePlanner.planMethod(ownerClass, method);
                    Optional<InitializerImplementationPlan> initializerPlan =
                            initializerPlanner.plan(initializerDecision, raw);
                    if (initializerPlan.isPresent()) {
                        initializerPlans.put(method.methodKey(), initializerPlan.orElseThrow());
                        optimizationInput = initializerPlan.orElseThrow().nativeBody();
                    }
                }
            }
            var optimizedResult = optimizationPipeline.run(optimizationInput, PassContext.empty());
            diagnostics.addAll(optimizedResult.diagnostics());
            IrMethod optimized = optimizedResult.artifact().orElse(optimizationInput);
            optimizedIr.put(method.methodKey(), optimized);
            var protectionResult = protectionPipeline.runDetailed(
                    optimized,
                    xyz.melodysky.ir.pass.protection.ProtectionConfig.fromResolved(
                            config.protection(),
                            irProtectionSeed));
            diagnostics.addAll(protectionResult.diagnostics());
            protectionReports.addAll(protectionResult.reports());
            protectedIr.put(method.methodKey(), protectionResult.method());
        }
        buildProgress.methodLoweringComplete(requestedMethodCount);

        buildProgress.nativePlanning(protectedIr.size());
        List<MethodRewriteDecision> rewriteDecisions = rewriteDecisions(program, selection.requestedMethods(), ssaResults);
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(rewriteDecisions, wrapperSymbolSeed);
        NativeImplementationPlanner implementationPlanner =
                new NativeImplementationPlanner(
                        llvmNameMangler,
                        businessStringSymbols);
        Set<String> availableProgramMethodKeys = program.classes().stream()
                .flatMap(parsedClass -> parsedClass.methods().stream())
                .map(ParsedMethod::methodKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        NativeImplementationPlan preliminaryImplementationPlan = implementationPlanner.plan(
                registrationPlan,
                rewriteDecisions,
                protectedIr,
                availableProgramMethodKeys,
                Set.of(),
                initializerPlans);
        ProgramIrProtectionResult programProtection =
                new ProgramIrProtectionCoordinator(llvmNameMangler).run(
                        protectedIr,
                        preliminaryImplementationPlan,
                        program,
                        reflectionPlan,
                        callGraph,
                        config.protection().ir(),
                        programProtectionSeed);
        diagnostics.addAll(programProtection.diagnostics());
        protectionReports.addAll(programProtection.reports());
        protectedIr.clear();
        protectedIr.putAll(programProtection.javaMethods());
        FieldInternalizationPipelineResult fieldInternalization =
                new FieldInternalizationPipeline().run(
                        config,
                        program,
                        protectedIr,
                        preliminaryImplementationPlan,
                        fieldInternalizationSeed,
                        wholeProgramPolicy);
        diagnostics.addAll(fieldInternalization.diagnostics());
        protectedIr.clear();
        protectedIr.putAll(fieldInternalization.methods());
        NativeFieldInternalizationPlan fieldInternalizationPlan =
                fieldInternalization.plan();
        LinkedHashMap<String, IrMethod> nativeIrBuilder = new LinkedHashMap<>(protectedIr);
        nativeIrBuilder.putAll(programProtection.compilerInternalMethods());
        Map<String, IrMethod> nativeIr =
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(nativeIrBuilder));
        LinkedHashSet<String> nativeAvailableMethodKeys = new LinkedHashSet<>(availableProgramMethodKeys);
        nativeAvailableMethodKeys.addAll(programProtection.compilerInternalMethods().keySet());
        NativeImplementationPlan implementationPlan = implementationPlanner.plan(
                registrationPlan,
                rewriteDecisions,
                nativeIr,
                nativeAvailableMethodKeys,
                programProtection.compilerInternalMethods().keySet(),
                initializerPlans);
        FinalNativeCoverageResult finalNativeCoverage =
                new FinalNativeCoverageResolver().resolve(
                        rewriteDecisions,
                        implementationPlan,
                        ssaResults);
        diagnostics.addAll(finalNativeCoverage.diagnostics());
        rewriteDecisions = finalNativeCoverage.implementedRewriteDecisions();
        implementationPlan = finalNativeCoverage.finalImplementationPlan();
        registrationPlan = implementationPlan.registrationPlan();
        ssaResults.clear();
        ssaResults.addAll(finalNativeCoverage.finalSsaResults());
        implementationPlan = protectTemplateStringConstants(
                implementationPlan,
                diagnostics,
                protectionReports,
                config,
                templateStringSeed);
        List<Diagnostic> fieldFinalPlanDiagnostics =
                new FieldInternalizationFinalPlanValidator().validate(
                        fieldInternalizationPlan,
                        implementationPlan);
        diagnostics.addAll(fieldFinalPlanDiagnostics);
        protectionReports.add(protectionEvidenceAssembler.fieldInternalization(
                fieldInternalization,
                fieldFinalPlanDiagnostics,
                fieldInternalizationSeed));
        boolean methodTableHidingEnabled = config.protection().enabled()
                && config.protection().ir().enabled()
                && config.protection().ir().methodTableHiding();
        MethodTableHidingPlan methodTableHidingPlan = new MethodTableHidingPlanner().plan(
                implementationPlan.registrationPlan(),
                methodTableHidingEnabled,
                methodTableSeed);
        protectionReports.add(protectionEvidenceAssembler.methodTableHiding(
                methodTableHidingEnabled,
                implementationPlan,
                methodTableHidingPlan,
                methodTableSeed));
        RuntimeLoaderPlan runtimeLoaderPlan = RuntimeLoaderPlan.create(
                config.embeddedLibraryDirectory(),
                fieldInternalizationPlan.referenceSidecarSize());
        NativeLlvmCompilation llvmCompilation = new NativeLlvmCompiler(
                        llvmLowerer,
                        llvmEmitter)
                .compile(
                        implementationPlan,
                        nativeIr,
                        llvmProtectionConfig,
                        buildProgress.llvmCompilationProgress());
        for (NativeLlvmModuleCompilation compiledModule : llvmCompilation.modules()) {
            List<IrMethod> reportMethods = compiledModule.registeredMethods();
            LlvmBlockLayoutPerturbationResult blockLayoutResult =
                    compiledModule.blockLayout();
            protectionReports.add(protectionEvidenceAssembler.llvmModel(
                    "LLVM_BLOCK_LAYOUT_PERTURBATION",
                    llvmBlockLayoutPerturbationEnabled,
                    "LLVM_BLOCK_LAYOUT_PERTURBATION",
                    "LLVM_BLOCK_LAYOUT_NO_CANDIDATE",
                    reportMethods,
                    blockLayoutResult.affectedFunctions(),
                    blockLayoutResult.validationIssues(),
                    llvmNameMangler,
                    llvmProtectionSeed));
            protectionEvidenceAssembler.reportLlvmValidationFailure(
                    diagnostics,
                    "LLVM_BLOCK_LAYOUT_PERTURBATION",
                    blockLayoutResult.validationIssues());

            LlvmOpaquePredicateResult opaquePredicateResult =
                    compiledModule.opaquePredicates();
            protectionReports.add(protectionEvidenceAssembler.llvmModel(
                    "LLVM_OPAQUE_PREDICATES",
                    llvmOpaquePredicatesEnabled,
                    "LLVM_OPAQUE_PREDICATES",
                    "LLVM_OPAQUE_PREDICATES_NO_CANDIDATE",
                    reportMethods,
                    opaquePredicateResult.affectedFunctions(),
                    opaquePredicateResult.validationIssues(),
                    llvmNameMangler,
                    llvmProtectionSeed));
            protectionEvidenceAssembler.reportLlvmValidationFailure(
                    diagnostics,
                    "LLVM_OPAQUE_PREDICATES",
                    opaquePredicateResult.validationIssues());

            LlvmIrCallIndirectionResult irCallBackendResult =
                    compiledModule.irCallIndirection();
            if (config.protection().enabled()
                    && config.protection().ir().enabled()
                    && config.protection().ir().callIndirection()) {
                protectionReports.add(protectionEvidenceAssembler.irCallIndirectionBackend(
                        reportMethods,
                        irCallBackendResult,
                        llvmNameMangler,
                        llvmProtectionSeed));
            }
            protectionEvidenceAssembler.reportLlvmValidationFailure(
                    diagnostics,
                    "IR_CALL_INDIRECTION_BACKEND",
                    irCallBackendResult.validationIssues());

            LlvmCallIndirectionResult callIndirectionResult =
                    compiledModule.llvmCallIndirection();
            protectionReports.add(protectionEvidenceAssembler.llvmCallIndirection(
                    llvmCallIndirectionEnabled,
                    reportMethods,
                    callIndirectionResult,
                    llvmNameMangler,
                    llvmProtectionSeed));
            LlvmGlobalLayoutResult globalLayoutResult =
                    compiledModule.globalLayout();
            protectionReports.add(protectionEvidenceAssembler.llvmGlobalLayout(
                    llvmGlobalLayoutEnabled,
                    reportMethods,
                    globalLayoutResult,
                    llvmProtectionSeed));
            protectionEvidenceAssembler.reportLlvmValidationFailure(
                    diagnostics,
                    "LLVM_GLOBAL_LAYOUT",
                    globalLayoutResult.validationIssues());
            protectionReports.add(
                    protectionEvidenceAssembler.llvmNameObfuscation(
                            llvmNameObfuscationEnabled,
                            reportMethods,
                            compiledModule.module().functions().stream()
                                    .map(function -> function.name())
                                    .toList(),
                            llvmNameSeed));
        }
        Map<String, String> llvmTextByClass = llvmCompilation.textByOwner();

        Map<String, LoweringStatus> statuses = methodStatuses(ssaResults);
        IntermediateArtifactLayout layout = new IntermediateArtifactLayoutPlanner().plan(classArtifactInputs(program, statuses));
        buildProgress.intermediateWriting(config.intermediates().enabled());
        writeIntermediates(
                workspaceRoot,
                config.intermediates(),
                layout,
                cfgByMethod,
                rawIr,
                optimizedIr,
                nativeIr,
                llvmTextByClass,
                callGraph,
                runtimeTypes,
                metadataIndex,
                reflectionPlan);

        buildProgress.targetPreflight(config.targets().size());
        NativeBuildPlan nativeBuildPlan = new NativeBuildPlanner().plan(
                workspaceRoot,
                NativeLibraryName.derive(config.protection().seed()),
                config.targets());
        diagnostics.addAll(targetPreflightDiagnostics(nativeBuildPlan));
        List<SkippedMethod> skippedMethods =
                new SkippedMethodCollector().collect(ssaResults);
        if (!diagnostics.hasErrors() && !skippedMethods.isEmpty()) {
            buildProgress.beforeUserInput();
        }
        SkippedMethodGate.Result skippedMethodGate =
                new SkippedMethodGate().evaluate(
                        skippedMethods,
                        diagnostics.hasErrors(),
                        skippedMethodApproval);
        diagnostics.addAll(skippedMethodGate.diagnostics());
        SkippedMethodGateDecision skippedMethodGateDecision =
                skippedMethodGate.decision();

        Optional<ZigNativeBuildResult> nativeBuildResult = Optional.empty();
        if (!diagnostics.hasErrors()) {
            buildProgress.nativeBuild(
                    nativeBuildPlan,
                    !implementationPlan.implementations().isEmpty());
            nativeBuildResult = new ZigNativeLibraryBuilder(
                    llvmNameMangler,
                    llvmProtectionConfig,
                    config.protection().enabled()
                            && config.protection().ir().enabled()
                            && config.protection().ir().methodTableHiding(),
                    config.protection().enabled()
                            && config.protection().binary().enabled()
                            && config.protection().binary().strip()).build(
                    workspaceRoot,
                    runtimeLoaderPlan,
                    nativeBuildPlan,
                    implementationPlan,
                    nativeIr,
                    buildProgress.nativeBuildProgress(),
                    methodTableHidingPlan,
                    llvmCompilation,
                    nativeTextBuildKey,
                    businessNativeTextBuildKey,
                    registrationBuildKey);
        }
        List<SymbolAuditReportWriter.LibraryAuditReport> symbolAudits =
                symbolAudits(nativeBuildPlan, nativeBuildResult);
        diagnostics.addAll(symbolAuditDiagnostics(symbolAudits));
        String loaderInternalName = nativeBuildResult
                .map(ignored -> runtimeLoaderPlan.internalName())
                .orElse(null);
        Path outputJar = workspaceLayout.outputJar(config.jarFile());
        buildProgress.jarPackaging(outputJar, diagnostics.hasErrors());
        if (!diagnostics.hasErrors()) {
            Map<String, byte[]> rewrittenEntries = rewriteClasses(
                    program,
                    rewriteDecisions,
                    implementationPlan,
                    fieldInternalizationPlan,
                    diagnostics,
                    loaderInternalName);
            Map<String, byte[]> addedEntries = addedJarEntries(config, nativeBuildResult, runtimeLoaderPlan);
            repackager.write(
                    config.jarFile(),
                    outputJar,
                    rewrittenEntries,
                    addedEntries,
                    config.signaturePolicy());
            if (config.signaturePolicy() == xyz.melodysky.config.SignaturePolicy.RESIGN) {
                JarSignatureResignResult resignResult =
                        new JarSignatureResigner(signatureEnvironment).sign(outputJar, config.signing());
                if (resignResult.successful()) {
                    signatureAction = SignatureActionReport.resigned(
                            signatureAction.signedInput(),
                            signatureAction.removedEntries());
                } else {
                    signatureAction = SignatureActionReport.resignFailed(
                            signatureAction.signedInput(),
                            signatureAction.removedEntries(),
                            resignResult.reasonCode(),
                            resignResult.reason());
                    Files.deleteIfExists(outputJar);
                    diagnostics.add(Diagnostic.error(
                                    DiagnosticStage.PACKAGING,
                                    xyz.melodysky.diagnostic.DiagnosticCode.SIGNATURE_RESIGN_FAILED,
                                    resignResult.reason())
                            .withDecision("failed"));
                }
            }
        }

        List<EmbeddedLibraryReport> embeddedLibraryReports = embeddedLibraries(config, nativeBuildResult);
        Files.writeString(workspaceRoot.resolve("reports/packaging-report.json"), new PackagingReportWriter().packagingJson(
                workspaceRoot.relativize(outputJar),
                config.signaturePolicy(),
                nativeBuildResult.isPresent() ? List.of(runtimeLoaderPlan.internalName()) : List.of(),
                rewriteDecisions,
                embeddedLibraryReports,
                implementationPlan.registrationPlan().entries(),
                methodTableHidingPlan,
                nativeBuildResult.map(ZigNativeBuildResult::exportedSymbols).orElse(List.of()),
                nativeBuildResult.orElse(null),
                nativeBuildPlan,
                preservationReport,
                signatureAction));
        ArtifactAuditResult artifactAuditResult = artifactAudit.skipped(
                "FINAL_ARTIFACT_NOT_WRITTEN",
                "pipeline did not reach final output JAR artifact audit");
        buildProgress.artifactAudit(outputJar, diagnostics.hasErrors());
        if (!diagnostics.hasErrors()) {
            artifactAuditResult = artifactAudit.audit(
                    workspaceRoot,
                    outputJar,
                    config.embeddedLibraryDirectory(),
                    embeddedLibraryReports,
                    nativeBuildResult.map(ZigNativeBuildResult::exportedSymbols).orElse(List.of()),
                    sensitivePlaintextFacts(protectionReports, implementationPlan),
                    fieldInternalizationPlan);
            if (!artifactAuditResult.passed()) {
                Files.deleteIfExists(outputJar);
                diagnostics.add(Diagnostic.error(
                                DiagnosticStage.ARTIFACT_AUDIT,
                                xyz.melodysky.diagnostic.DiagnosticCode.ARTIFACT_AUDIT_FAILED,
                                "artifact audit failed; final output JAR was not retained: "
                                        + failedArtifactAuditReasons(artifactAuditResult))
                        .withDecision("failed"));
            }
        }

        buildProgress.reportWriting();
        writeReports(
                config,
                workspaceRoot,
                diagnostics,
                selection,
                ssaResults,
                program,
                layout,
                rewriteDecisions,
                registrationPlan,
                implementationPlan,
                outputJar,
                symbolAudits,
                nativeBuildPlan,
                nativeBuildResult,
                runtimeLoaderPlan,
                embeddedLibraryReports,
                artifactAuditResult,
                protectionReports,
                fieldInternalization,
                methodTableHidingPlan,
                preservationReport,
                signatureAction,
                skippedMethodGateDecision);

        return new MainlinePipelineResult(
                workspaceRoot,
                outputJar,
                diagnostics.diagnostics(),
                nativeBuildPlan,
                registrationPlan,
                !diagnostics.hasErrors());
    }

    private NativeImplementationPlan protectTemplateStringConstants(
            NativeImplementationPlan implementationPlan,
            DiagnosticBag diagnostics,
            List<ProtectionPassReport> protectionReports,
            ResolvedConfig config,
            long seed) {
        xyz.melodysky.ir.pass.protection.ProtectionConfig resolved =
                xyz.melodysky.ir.pass.protection.ProtectionConfig.fromResolved(config.protection(), seed);
        xyz.melodysky.ir.pass.protection.ProtectionConfig stringOnly =
                new xyz.melodysky.ir.pass.protection.ProtectionConfig(
                        resolved.enabled(),
                        seed,
                        false,
                        resolved.stringEncryption(),
                        false,
                        false,
                        false,
                        false);
        ProtectionPipeline templatePipeline = new ProtectionPipeline(List.of(new StringEncryptionPass()));
        ArrayList<NativeMethodImplementation> implementations = new ArrayList<>();
        for (NativeMethodImplementation implementation : implementationPlan.implementations()) {
            if (implementation.path() != NativeImplementationPath.TEMPLATE_JNI_PATH
                    || implementation.templateIrMethod().isEmpty()) {
                implementations.add(implementation);
                continue;
            }
            var protectionResult = templatePipeline.runDetailed(
                    implementation.templateIrMethod().orElseThrow(),
                    stringOnly);
            diagnostics.addAll(protectionResult.diagnostics());
            protectionReports.addAll(protectionResult.reports());
            implementations.add(new NativeMethodImplementation(
                    implementation.entry(),
                    implementation.decision(),
                    implementation.path(),
                    implementation.llvmFunctionSymbol(),
                    implementation.reasonCode(),
                    implementation.passesJniEnv(),
                    implementation.passesOwnerClass(),
                    implementation.fieldKeys(),
                    implementation.directCallTargets(),
                    implementation.allocationKeys(),
                    implementation.typeCheckKeys(),
                    implementation.classObjectKeys(),
                    implementation.runtimeMetadataKeys(),
                    implementation.constructorCallKeys(),
                    implementation.staticCallKeys(),
                    implementation.dispatchKeys(),
                    implementation.stringHelperSymbols(),
                    Optional.of(protectionResult.method()),
                    implementation.initializerPlan()));
        }
        return new NativeImplementationPlan(
                implementations,
                implementationPlan.unavailableReasonCodes(),
                implementationPlan.localReferencePlans());
    }

    private MainlinePipelineResult failed(
            ResolvedConfig config,
            Path workspaceRoot,
            DiagnosticBag diagnostics,
            WholeProgramAnalysisPolicy wholeProgramPolicy)
            throws IOException {
        return failed(
                config,
                workspaceRoot,
                diagnostics,
                JarPreservationReport.empty(),
                SignatureActionReport.none(false),
                wholeProgramPolicy);
    }

    private MainlinePipelineResult failed(
            ResolvedConfig config,
            Path workspaceRoot,
            DiagnosticBag diagnostics,
            JarPreservationReport preservationReport,
            SignatureActionReport signatureAction,
            WholeProgramAnalysisPolicy wholeProgramPolicy)
            throws IOException {
        NativeBuildPlan buildPlan = new NativeBuildPlanner().plan(workspaceRoot, "j2ll_failed", config.targets());
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlan(List.of());
        Path outputJar = new WorkspaceLayout(workspaceRoot).outputJar(config.jarFile());
        Files.createDirectories(outputJar.getParent());
        Files.writeString(workspaceRoot.resolve("reports/diagnostics.json"),
                new ReportJsonWriter().diagnosticsJson(diagnostics.diagnostics()));
        Files.writeString(workspaceRoot.resolve("reports/failure-report.json"),
                new FailureReportWriter().json(diagnostics.diagnostics(), false));
        Files.writeString(workspaceRoot.resolve("reports/packaging-report.json"), new PackagingReportWriter().packagingJson(
                workspaceRoot.relativize(outputJar),
                config.signaturePolicy(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                buildPlan,
                preservationReport,
                signatureAction));
        Files.writeString(workspaceRoot.resolve("reports/artifact-audit.json"),
                new ArtifactAuditReportWriter().json(artifactAudit.skipped(
                        "FINAL_ARTIFACT_NOT_WRITTEN",
                        "pipeline failed before final output JAR was written")));
        boolean fieldInternalizationEnabled = config.protection().enabled()
                && config.protection().ir().enabled()
                && config.protection().ir().fieldInternalization();
        Files.writeString(
                workspaceRoot.resolve("reports/field-internalization-report.json"),
                new FieldInternalizationReportWriter().json(
                        new NativeFieldInternalizationPlan(List.of()),
                        fieldInternalizationEnabled,
                        false,
                        new NativeImplementationPlan(List.of()),
                        config.worldModel(),
                        fieldInternalizationEnabled
                                ? wholeProgramPolicy.scopeFor(
                                        WholeProgramAnalysisFeature.FIELD_INTERNALIZATION,
                                        config.worldModel())
                                : WholeProgramAnalysisScope.NOT_REQUIRED,
                        false,
                        false));
        writeReleaseReadinessReports(workspaceRoot);
        writeReportSummaryAndIndex(workspaceRoot, "build", false);
        return new MainlinePipelineResult(workspaceRoot, outputJar, diagnostics.diagnostics(), buildPlan, registrationPlan, false);
    }

    private List<MethodRewriteDecision> rewriteDecisions(
            ParsedProgram program,
            List<ParsedMethod> requestedMethods,
            List<SsaMethodResult> ssaResults) {
        Map<String, ParsedMethod> requested = new HashMap<>();
        requestedMethods.forEach(method -> requested.put(method.methodKey(), method));
        Set<String> rewriteable = ssaResults.stream()
                .filter(result -> result.status()
                        == LoweringStatus.NATIVE_LOWERED)
                .map(result -> result.sourceMethod().methodKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        ArrayList<MethodRewriteDecision> decisions = new ArrayList<>();
        for (ParsedClass parsedClass : program.classes()) {
            for (MethodRewriteDecision decision : rewritePlanner.planClass(parsedClass)) {
                if (requested.containsKey(decision.method().methodKey())
                        && rewriteable.contains(decision.method().methodKey())) {
                    decisions.add(decision);
                }
            }
        }
        return decisions;
    }

    private Map<String, byte[]> rewriteClasses(
            ParsedProgram program,
            List<MethodRewriteDecision> decisions,
            NativeImplementationPlan implementationPlan,
            NativeFieldInternalizationPlan fieldInternalizationPlan,
            DiagnosticBag diagnostics,
            String loaderInternalName) {
        Set<String> implementedMethodKeys = implementationPlan.implementations().stream()
                .map(NativeMethodImplementation::methodKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<String, List<MethodRewriteDecision>> byClass = new HashMap<>();
        Map<String, xyz.melodysky.toolchain.initializer.InitializerImplementationPlan> initializerPlans =
                implementationPlan.implementations().stream()
                        .filter(implementation -> implementation.initializerPlan().isPresent())
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                NativeMethodImplementation::methodKey,
                                implementation -> implementation.initializerPlan().orElseThrow()));
        decisions.stream()
                .filter(decision -> implementedMethodKeys.contains(decision.method().methodKey()))
                .filter(decision -> decision.strategy() == MethodRewriteStrategy.NATIVE_ORIGINAL
                        || decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB
                        || decision.strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB)
                .forEach(decision -> byClass.computeIfAbsent(decision.method().owner(), ignored -> new ArrayList<>())
                        .add(decision));
        Map<String, byte[]> rewritten = new LinkedHashMap<>();
        InternalizedFieldClassTransform fieldTransform = new InternalizedFieldClassTransform();
        for (ParsedClass parsedClass : program.classes()) {
            List<MethodRewriteDecision> classDecisions = byClass.getOrDefault(parsedClass.internalName(), List.of());
            boolean hasInternalizedField = fieldInternalizationPlan.approvedFieldIds().stream()
                    .anyMatch(field -> field.owner().equals(parsedClass.internalName()));
            if (classDecisions.isEmpty() && !hasInternalizedField) {
                continue;
            }
            ClassRewriteResult result = loaderInternalName == null
                    ? classRewriter.rewrite(parsedClass, classDecisions, initializerPlans, null)
                    : classRewriter.rewrite(
                            parsedClass,
                            classDecisions,
                            initializerPlans,
                            loaderInternalName);
            diagnostics.addAll(result.diagnostics());
            var fieldResult = fieldTransform.apply(
                    result.classBytes(),
                    parsedClass.internalName(),
                    fieldInternalizationPlan,
                    parsedClass.methods().stream()
                            .anyMatch(method -> method.name().equals("<clinit>")));
            diagnostics.addAll(fieldResult.diagnostics());
            if (!result.applied().isEmpty() || !fieldResult.removedFieldKeys().isEmpty()) {
                rewritten.put(parsedClass.sourceEntry(), fieldResult.classBytes());
            }
        }
        return rewritten;
    }

    private Map<String, byte[]> addedJarEntries(
            ResolvedConfig config,
            Optional<ZigNativeBuildResult> nativeBuildResult,
            RuntimeLoaderPlan runtimeLoaderPlan) throws IOException {
        Map<String, byte[]> added = new LinkedHashMap<>();
        if (nativeBuildResult.isPresent()) {
            ZigNativeBuildResult result = nativeBuildResult.orElseThrow();
            added.put(
                    runtimeLoaderPlan.entryName(),
                    new NativeLoaderClassGenerator().generate(runtimeLoaderPlan, result.artifacts()));
            for (NativeLibraryArtifact artifact : result.artifacts()) {
                added.put(artifact.jarPath(), Files.readAllBytes(artifact.libraryPath()));
            }
        }
        added.putAll(new J2llMetadataEntries().entries(config, nativeBuildResult));
        return added;
    }

    private void writeReports(
            ResolvedConfig config,
            Path workspaceRoot,
            DiagnosticBag diagnostics,
            SelectorMatchResult selection,
            List<SsaMethodResult> ssaResults,
            ParsedProgram program,
            IntermediateArtifactLayout layout,
            List<MethodRewriteDecision> rewriteDecisions,
            NativeRegistrationPlan registrationPlan,
            NativeImplementationPlan implementationPlan,
            Path outputJar,
            List<SymbolAuditReportWriter.LibraryAuditReport> symbolAudits,
            NativeBuildPlan nativeBuildPlan,
            Optional<ZigNativeBuildResult> nativeBuildResult,
            RuntimeLoaderPlan runtimeLoaderPlan,
            List<EmbeddedLibraryReport> embeddedLibraryReports,
            ArtifactAuditResult artifactAuditResult,
            List<ProtectionPassReport> protectionReports,
            FieldInternalizationPipelineResult fieldInternalization,
            MethodTableHidingPlan methodTableHidingPlan,
            JarPreservationReport preservationReport,
            SignatureActionReport signatureAction,
            SkippedMethodGateDecision skippedMethodGateDecision)
            throws IOException {
        Path reports = workspaceRoot.resolve("reports");
        NativeRegistrationPlan implementedRegistrationPlan = implementationPlan.registrationPlan();
        Files.writeString(workspaceRoot.resolve("config.resolved.json"), new ResolvedConfigReportWriter().json(config));
        Files.writeString(reports.resolve("diagnostics.json"), new ReportJsonWriter().diagnosticsJson(diagnostics.diagnostics()));
        if (diagnostics.hasErrors()) {
            Files.writeString(reports.resolve("failure-report.json"),
                    new FailureReportWriter().json(diagnostics.diagnostics(), false));
        }
        Files.writeString(
                reports.resolve("skipped-method-report.json"),
                new SkippedMethodReportWriter().json(
                        ssaResults,
                        skippedMethodGateDecision));
        Files.writeString(reports.resolve("lowering-report.json"), new ReportJsonWriter().loweringJson(
                loweringReportAssembler.assemble(
                        program,
                        layout,
                        ssaResults,
                        rewriteDecisions,
                        implementedRegistrationPlan,
                        implementationPlan),
                selection.ineligible(),
                selection.excluded()));
        Files.writeString(reports.resolve("packaging-report.json"), new PackagingReportWriter().packagingJson(
                workspaceRoot.relativize(outputJar),
                config.signaturePolicy(),
                nativeBuildResult.isPresent() ? List.of(runtimeLoaderPlan.internalName()) : List.of(),
                rewriteDecisions,
                embeddedLibraryReports,
                implementedRegistrationPlan.entries(),
                methodTableHidingPlan,
                nativeBuildResult.map(ZigNativeBuildResult::exportedSymbols).orElse(List.of()),
                nativeBuildResult.orElse(null),
                nativeBuildPlan,
                preservationReport,
                signatureAction));
        Files.writeString(reports.resolve("artifact-audit.json"), new ArtifactAuditReportWriter().json(artifactAuditResult));
        Files.writeString(reports.resolve("protection-report.json"),
                new ProtectionReportWriter().json(
                        config.protection().seedMode(),
                        BuildProtectionIdentity.from(config.protection()).identityHash(),
                        classifiedProtectionReports(protectionReports, implementationPlan)));
        Files.writeString(
                reports.resolve("field-internalization-report.json"),
                new FieldInternalizationReportWriter().json(
                        fieldInternalization.plan(),
                        config.protection().enabled()
                                && config.protection().ir().enabled()
                                && config.protection().ir().fieldInternalization(),
                        !diagnostics.hasErrors() && Files.isRegularFile(outputJar),
                        implementationPlan,
                        config.worldModel(),
                        fieldInternalization.analysisScope(),
                        fieldInternalization.classPathAnalyzed(),
                        fieldInternalization.analysisScope()
                                != WholeProgramAnalysisScope.NOT_REQUIRED));
        Files.writeString(reports.resolve("symbol-audit.json"), new SymbolAuditReportWriter().json(symbolAudits));
        writeReleaseReadinessReports(workspaceRoot);
        writeReportSummaryAndIndex(
                workspaceRoot,
                "build",
                !diagnostics.hasErrors() && Files.isRegularFile(outputJar));
    }

    private List<SensitivePlaintextFact> sensitivePlaintextFacts(
            List<ProtectionPassReport> protectionReports,
            NativeImplementationPlan implementationPlan) {
        Map<String, NativeMethodImplementation> implementationsByMethod = implementationPlan.implementations().stream()
                .collect(java.util.stream.Collectors.toMap(
                        NativeMethodImplementation::methodKey,
                        implementation -> implementation,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new));
        Set<String> llvmNativeMethods = implementationsByMethod.values().stream()
                .filter(implementation -> implementation.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .filter(implementation -> implementation.stringHelperSymbols().isEmpty())
                .map(NativeMethodImplementation::methodKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<String> semanticSensitiveMethods = semanticSensitiveMethods(protectionReports);
        List<SensitivePlaintextFact> protectionFacts = protectionReports.stream()
                .flatMap(report -> report.sensitivePlaintextFacts().stream())
                .map(fact -> classifiedSensitiveFact(
                        fact, llvmNativeMethods, semanticSensitiveMethods, implementationsByMethod))
                .toList();
        return java.util.stream.Stream.concat(
                        protectionFacts.stream(),
                        nativeMetadataPlaintextFacts(implementationPlan).stream())
                .distinct()
                .sorted(java.util.Comparator
                        .comparing(SensitivePlaintextFact::literalHash)
                        .thenComparing(SensitivePlaintextFact::sourceMethod)
                        .thenComparing(SensitivePlaintextFact::pathKind)
                        .thenComparing(SensitivePlaintextFact::gateMode)
                        .thenComparing(SensitivePlaintextFact::promotionReason))
                .toList();
    }

    private List<SensitivePlaintextFact> nativeMetadataPlaintextFacts(
            NativeImplementationPlan implementationPlan) {
        ArrayList<SensitivePlaintextFact> facts = new ArrayList<>();
        for (NativeMethodImplementation implementation : implementationPlan.implementations()) {
            String methodKey = implementation.methodKey();
            addNativeMetadataFact(facts, implementation.entry().registrationOwner(), methodKey);
            if (implementation.entry().methodName().length() >= 8) {
                addNativeMetadataFact(facts, implementation.entry().methodName(), methodKey);
            }
            java.util.stream.Stream.of(
                            implementation.fieldKeys(),
                            implementation.allocationKeys(),
                            implementation.typeCheckKeys(),
                            implementation.classObjectKeys(),
                            implementation.runtimeMetadataKeys(),
                            implementation.constructorCallKeys(),
                            implementation.staticCallKeys(),
                            implementation.dispatchKeys())
                    .flatMap(List::stream)
                    .forEach(value -> addInternalNamesAsMetadataFacts(facts, value, methodKey));
            for (String fieldKey : implementation.fieldKeys()) {
                int ownerEnd = fieldKey.indexOf('#');
                int descriptorStart = fieldKey.indexOf('!');
                if (ownerEnd >= 0 && descriptorStart > ownerEnd + 1) {
                    String fieldName = fieldKey.substring(ownerEnd + 1, descriptorStart);
                    if (fieldName.length() >= 8) {
                        addNativeMetadataFact(facts, fieldName, methodKey);
                    }
                }
            }
        }
        for (String runtimeMetadata : List.of(
                "java/lang/NoSuchFieldError",
                "unknown j2ll field token",
                "java/lang/NullPointerException",
                "java/lang/ArithmeticException",
                "/ by zero",
                "java/lang/ArrayIndexOutOfBoundsException")) {
            addNativeMetadataFact(facts, runtimeMetadata, "<native-runtime>");
        }
        return facts.stream().distinct().toList();
    }

    private void addInternalNamesAsMetadataFacts(
            List<SensitivePlaintextFact> facts,
            String value,
            String methodKey) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:[A-Za-z_$][A-Za-z0-9_$]*/)+[A-Za-z_$][A-Za-z0-9_$]*")
                .matcher(value);
        while (matcher.find()) {
            addNativeMetadataFact(facts, matcher.group(), methodKey);
        }
    }

    private void addNativeMetadataFact(
            List<SensitivePlaintextFact> facts,
            String plaintext,
            String methodKey) {
        if (plaintext == null || plaintext.isBlank()) {
            return;
        }
        facts.add(new SensitivePlaintextFact(
                plaintext,
                null,
                methodKey,
                "nativeMetadataStringEncoding",
                List.of("generated-c", "native-library"),
                "NATIVE_METADATA_STRING",
                "blocking",
                "GENERATED_C",
                "NATIVE_METADATA_PLAINTEXT",
                "nativeMetadataSurface"));
    }

    private Set<String> versionedClassNames(Path jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile.toFile(), false)) {
            return jar.stream()
                    .map(java.util.jar.JarEntry::getName)
                    .filter(name -> name.startsWith("META-INF/versions/"))
                    .filter(name -> name.endsWith(".class"))
                    .map(name -> name.substring(name.indexOf('/', "META-INF/versions/".length()) + 1))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        }
    }

    private List<ProtectionPassReport> classifiedProtectionReports(
            List<ProtectionPassReport> protectionReports,
            NativeImplementationPlan implementationPlan) {
        Map<String, NativeMethodImplementation> implementationsByMethod = implementationPlan.implementations().stream()
                .collect(java.util.stream.Collectors.toMap(
                        NativeMethodImplementation::methodKey,
                        implementation -> implementation,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new));
        Set<String> llvmNativeMethods = implementationsByMethod.values().stream()
                .filter(implementation -> implementation.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .filter(implementation -> implementation.stringHelperSymbols().isEmpty())
                .map(NativeMethodImplementation::methodKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<String> semanticSensitiveMethods = semanticSensitiveMethods(protectionReports);
        return protectionReports.stream()
                .map(report -> new ProtectionPassReport(
                        report.passName(),
                        report.layer(),
                        report.status(),
                        report.reasonCode(),
                        report.affectedMethods(),
                        report.affectedSymbols(),
                        report.seed(),
                        report.sensitivePlaintextFacts().stream()
                                .map(fact -> classifiedSensitiveFact(
                                        fact, llvmNativeMethods, semanticSensitiveMethods, implementationsByMethod))
                                .toList(),
                        report.coverageFacts()))
                .toList();
    }

    private Set<String> semanticSensitiveMethods(List<ProtectionPassReport> protectionReports) {
        return protectionReports.stream()
                .filter(report -> report.reasonCode().equals("PROTECTION_SEMANTICALLY_SENSITIVE_METHOD"))
                .flatMap(report -> report.affectedMethods().stream())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private SensitivePlaintextFact classifiedSensitiveFact(
            SensitivePlaintextFact fact,
            Set<String> llvmNativeMethods,
            Set<String> semanticSensitiveMethods,
            Map<String, NativeMethodImplementation> implementationsByMethod) {
        if (!isStableBlockingPlaintext(fact.plaintext())) {
            NativeMethodImplementation implementation = implementationsByMethod.get(fact.sourceMethod());
            return fact.withAuditClassification(
                    implementation == null ? "HELPER_PATH" : implementation.path().name(),
                    "observedOnly",
                    "PLAINTEXT_LITERAL_TOO_SHORT_FOR_BLOCKING_GATE",
                    "metadataSensitiveObservedOnly");
        }
        NativeMethodImplementation implementation = implementationsByMethod.get(fact.sourceMethod());
        if (isJvmMetadataBridgePlaintext(implementation)) {
            return fact.withAuditClassification(
                    implementation == null ? "HELPER_PATH" : implementation.path().name(),
                    "observedOnly",
                    "JVM_METADATA_BRIDGE_PLAINTEXT",
                    "metadataSensitiveObservedOnly");
        }
        if (semanticSensitiveMethods.contains(fact.sourceMethod())) {
            return fact.withAuditClassification(
                    implementation == null ? "HELPER_PATH" : implementation.path().name(),
                    "observedOnly",
                    "PROTECTION_SEMANTICALLY_SENSITIVE_METHOD",
                    "metadataSensitiveObservedOnly");
        }
        if (implementation != null && implementation.reasonCode().equals("LLVM_EXCEPTION_HELPER_IR")) {
            return fact.withAuditClassification(
                    implementation.path().name(),
                    "observedOnly",
                    "EXCEPTION_HELPER_PLAINTEXT",
                    "metadataSensitiveObservedOnly");
        }
        if (llvmNativeMethods.contains(fact.sourceMethod())) {
            return fact.withAuditClassification(
                    "LLVM_NATIVE_PATH",
                    "blocking",
                    "LLVM_NATIVE_PATH_CONNECTED_SURFACE",
                    "llvmNativeSurface");
        }
        if (implementation != null
                && implementation.path() == NativeImplementationPath.TEMPLATE_JNI_PATH
                && implementation.reasonCode().equals("GENERIC_CONSTRUCTOR_BODY_HELPER")) {
            return fact.withAuditClassification(
                    "TEMPLATE_JNI_PATH_STABLE_SURFACE",
                    "blocking",
                    "TEMPLATE_CONSTRUCTOR_BODY_STABLE_SURFACE",
                    "templateStableSurface");
        }
        if (implementation != null
                && implementation.stringHelperSymbols().stream()
                        .map(this::runtimeHelperBaseSymbol)
                        .anyMatch(symbol -> symbol.equals("j2ll_rt_string_constant")
                                || symbol.startsWith("j2ll_rt_string_constant_"))) {
            return fact.withAuditClassification(
                    "HELPER_PATH_STABLE_GENERATED_C_SURFACE",
                    "blocking",
                    "STRING_CONCAT_CONSTANT_CARRIER_STABLE_SURFACE",
                    "stableGeneratedCSurface");
        }
        return fact.withAuditClassification(
                implementation == null ? "HELPER_PATH" : implementation.path().name(),
                "observedOnly",
                "NON_BLOCKING_PATH_KIND_UNTIL_SURFACE_CONNECTED",
                "metadataSensitiveObservedOnly");
    }

    private boolean isStableBlockingPlaintext(String plaintext) {
        return plaintext != null && plaintext.length() >= 8;
    }

    private boolean isJvmMetadataBridgePlaintext(NativeMethodImplementation implementation) {
        if (implementation == null) {
            return false;
        }
        return java.util.stream.Stream.of(
                        implementation.staticCallKeys(),
                        implementation.dispatchKeys(),
                        implementation.stringHelperSymbols())
                .flatMap(java.util.Collection::stream)
                .anyMatch(symbol -> symbol.contains("java/lang/invoke/MethodHandles")
                        || symbol.contains("java/lang/invoke/MethodType")
                        || symbol.contains("java/lang/invoke/MethodHandle")
                        || symbol.contains("java/util/ResourceBundle"));
    }

    private String runtimeHelperBaseSymbol(String symbol) {
        int separator = symbol.indexOf('|');
        return separator < 0 ? symbol : symbol.substring(0, separator);
    }

    private String implementationPath(String methodKey, NativeImplementationPlan implementationPlan) {
        return implementationPlan.implementations().stream()
                .filter(implementation -> implementation.methodKey().equals(methodKey))
                .map(implementation -> implementation.path().name())
                .findFirst()
                .orElse("HELPER_PATH");
    }

    private String failedArtifactAuditReasons(ArtifactAuditResult result) {
        return result.checks().stream()
                .filter(check -> check.status().equals("failed"))
                .map(check -> check.reasonCode() + "(" + check.message() + ")")
                .sorted()
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private void writeReleaseReadinessReports(Path workspaceRoot) throws IOException {
        Path reports = workspaceRoot.resolve("reports");
        Files.writeString(reports.resolve("support-matrix.json"), new SupportMatrixWriter().json());
        Files.writeString(reports.resolve("opcode-support-matrix.json"), new OpcodeSupportMatrixWriter().json());
        Files.writeString(reports.resolve("known-blockers.json"), new KnownBlockersWriter().json());
        Files.writeString(reports.resolve("release-readiness.json"),
                new ReleaseReadinessWriter().json(new ReleaseReadinessGate().evaluate(workspaceRoot)));
    }

    private void writeReportSummaryAndIndex(Path workspaceRoot, String mode, boolean finalArtifactWritten)
            throws IOException {
        new SummaryReportWriter().write(workspaceRoot, mode, finalArtifactWritten);
        new SummaryMarkdownWriter().write(workspaceRoot);
        new ReportIndexWriter().write(workspaceRoot);
        Files.writeString(workspaceRoot.resolve("reports/release-readiness.json"),
                new ReleaseReadinessWriter().json(new ReleaseReadinessGate().evaluate(workspaceRoot)));
        new SummaryReportWriter().write(workspaceRoot, mode, finalArtifactWritten);
        new SummaryMarkdownWriter().write(workspaceRoot);
        new ReportIndexWriter().write(workspaceRoot);
    }

    private List<Diagnostic> targetPreflightDiagnostics(NativeBuildPlan nativeBuildPlan) {
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        for (var preflight : nativeBuildPlan.targetPreflights()) {
            String message = "Zig target preflight " + preflight.target().directoryName()
                    + " -> " + preflight.status() + ": " + preflight.reason();
            Diagnostic diagnostic = preflight.buildable()
                    ? Diagnostic.info(DiagnosticStage.NATIVE_LINK, ToolchainDiagnostics.ZIG_TARGET_PREFLIGHT, message)
                    : Diagnostic.error(DiagnosticStage.NATIVE_LINK, ToolchainDiagnostics.ZIG_TARGET_UNBUILDABLE, message);
            diagnostics.add(diagnostic.withDecision(preflight.status()));
        }
        return List.copyOf(diagnostics);
    }

    private List<EmbeddedLibraryReport> embeddedLibraries(
            ResolvedConfig config,
            Optional<ZigNativeBuildResult> nativeBuildResult) {
        if (nativeBuildResult.isEmpty()) {
            return List.of();
        }
        EmbeddedLibraryLayout layout = new EmbeddedLibraryLayout();
        return config.targets().stream()
                .map(target -> new EmbeddedLibraryReport(
                        target.directoryName(),
                        nativeBuildResult
                                .flatMap(result -> result.artifactFor(target))
                                .map(NativeLibraryArtifact::jarPath)
                                .orElse(layout.jarPath(config.embeddedLibraryDirectory(), target)),
                        nativeBuildResult
                                .flatMap(result -> result.artifactFor(target))
                                .map(NativeLibraryArtifact::sha256)
                                .orElse("0000000000000000000000000000000000000000000000000000000000000000")))
                .toList();
    }

    private List<SymbolAuditReportWriter.LibraryAuditReport> symbolAudits(
            NativeBuildPlan nativeBuildPlan,
            Optional<ZigNativeBuildResult> nativeBuildResult) {
        if (nativeBuildResult.isEmpty()) {
            return List.of();
        }
        SymbolVisibilityPlanner visibilityPlanner = new SymbolVisibilityPlanner();
        return nativeBuildPlan.units().stream()
                .map(unit -> {
                    ExportList allowlist = visibilityPlanner.loaderExports(unit.target());
                    List<String> actualExports = nativeBuildResult
                            .flatMap(result -> result.artifactFor(unit.target()))
                            .map(NativeLibraryArtifact::exportedSymbols)
                            .orElse(List.of());
                    SymbolAuditResult result = new SymbolAudit().audit(allowlist, actualExports);
                    return new SymbolAuditReportWriter.LibraryAuditReport(unit.target(), unit.outputPath(), result);
                })
                .toList();
    }

    private List<Diagnostic> symbolAuditDiagnostics(
            List<SymbolAuditReportWriter.LibraryAuditReport> symbolAudits) {
        return symbolAudits.stream()
                .filter(audit -> !audit.result().passed())
                .map(audit -> Diagnostic.error(
                                DiagnosticStage.SYMBOL_AUDIT,
                                ToolchainDiagnostics.SYMBOL_AUDIT_FAILED,
                                "native export audit failed for " + audit.target().directoryName()
                                        + ": missing=" + audit.result().missingExports()
                                        + ", unexpected=" + audit.result().unexpectedExports())
                        .withDecision("failed"))
                .toList();
    }

    private Map<String, LoweringStatus> methodStatuses(List<SsaMethodResult> ssaResults) {
        Map<String, LoweringStatus> statuses = new HashMap<>();
        for (SsaMethodResult result : ssaResults) {
            statuses.put(result.sourceMethod().methodKey(), result.status());
        }
        return statuses;
    }

    private List<ClassArtifactInput> classArtifactInputs(ParsedProgram program, Map<String, LoweringStatus> statuses) {
        ArrayList<ClassArtifactInput> inputs = new ArrayList<>();
        for (ParsedClass parsedClass : program.classes()) {
            ArrayList<MethodArtifactInput> methods = new ArrayList<>();
            for (ParsedMethod method : parsedClass.methods()) {
                LoweringStatus status = statuses.get(method.methodKey());
                if (status == null) {
                    continue;
                }
                methods.add(new MethodArtifactInput(
                        method.owner(),
                        method.name(),
                        method.descriptor(),
                        status));
            }
            inputs.add(new ClassArtifactInput(parsedClass.internalName(), parsedClass.sourceEntry(), methods));
        }
        return inputs;
    }

    private void writeIntermediates(
            Path workspaceRoot,
            xyz.melodysky.config.IntermediatesConfig intermediates,
            IntermediateArtifactLayout layout,
            Map<String, MethodCfgResult> cfgByMethod,
            Map<String, IrMethod> rawIr,
            Map<String, IrMethod> optimizedIr,
            Map<String, IrMethod> protectedIr,
            Map<String, String> llvmTextByClass,
            CallGraph callGraph,
            RuntimeTypeResult runtimeTypes,
            RuntimeMetadataIndex metadataIndex,
            ReflectionPlan reflectionPlan) throws IOException {
        IntermediateArtifactIndexWriter indexWriter = new IntermediateArtifactIndexWriter();
        Files.createDirectories(workspaceRoot.resolve("intermediates"));
        if (!intermediates.enabled()) {
            Files.writeString(
                    workspaceRoot.resolve("intermediates/intermediates-manifest.json"),
                    indexWriter.manifestJson(workspaceRoot, intermediates, layout));
            return;
        }
        if (intermediates.includeDebugDumps()) {
            Files.createDirectories(workspaceRoot.resolve("intermediates/runtime"));
            Files.writeString(
                    workspaceRoot.resolve("intermediates/runtime/runtime-metadata.json"),
                    new RuntimeMetadataDumpWriter().write(metadataIndex, reflectionPlan));
        }
        for (ClassArtifact classArtifact : layout.classes()) {
            Path classDir = workspaceRoot.resolve("intermediates/classes").resolve(classArtifact.directory());
            Files.createDirectories(classDir.resolve("cfg"));
            Files.createDirectories(classDir.resolve("ir"));
            Files.createDirectories(classDir.resolve("llvm"));
            Files.createDirectories(classDir.resolve("c"));
            Files.createDirectories(classDir.resolve("reports"));
            Files.writeString(classDir.resolve("class-index.json"), indexWriter.classIndexJson(classArtifact));
            Files.writeString(classDir.resolve("method-index.json"), indexWriter.methodIndexJson(classArtifact, layout));
            Files.writeString(classDir.resolve("hierarchy.json"), "{\"schemaVersion\":1}\n");
            Files.writeString(classDir.resolve("call-sites.json"),
                    "{\"schemaVersion\":1,\"callSiteCount\":" + callGraph.callSites().size()
                            + ",\"instantiatedClassCount\":" + runtimeTypes.instantiatedClasses().size() + "}\n");
            for (MethodArtifact method : layout.methodsFor(classArtifact.internalName())) {
                String key = method.owner() + "#" + method.name() + "!" + method.descriptor();
                MethodCfgResult cfg = cfgByMethod.get(key);
                if (cfg != null && intermediates.includeDebugDumps()) {
                    Files.writeString(classDir.resolve("cfg").resolve(method.methodId() + ".cfg.txt"), cfg.toString());
                    Files.writeString(classDir.resolve("cfg").resolve(method.methodId() + ".cfg.json"),
                            "{\"schemaVersion\":1,\"method\":\"" + method.name() + "\"}\n");
                }
            }
            if (intermediates.includePerClassIr()) {
                Files.writeString(classDir.resolve("ir/raw.ssa.ir"), irDump(rawIr, classArtifact.internalName()));
                Files.writeString(classDir.resolve("ir/optimized.ssa.ir"), irDump(optimizedIr, classArtifact.internalName()));
                Files.writeString(classDir.resolve("ir/protected.ssa.ir"), irDump(protectedIr, classArtifact.internalName()));
            }
            if (intermediates.includePerClassLlvm()) {
                Files.writeString(classDir.resolve("llvm/class.ll"), llvmTextByClass.getOrDefault(classArtifact.internalName(), ""));
                Files.writeString(classDir.resolve("llvm/protected.class.ll"), llvmTextByClass.getOrDefault(classArtifact.internalName(), ""));
            }
            if (intermediates.includePerClassC()) {
                Files.writeString(classDir.resolve("c/class.c"), "/* planned C wrapper artifact */\n");
            }
            Files.writeString(classDir.resolve("reports/lowering.json"), "{\"schemaVersion\":1}\n");
            Files.writeString(classDir.resolve("reports/protection.json"), "{\"schemaVersion\":1}\n");
        }
        Files.writeString(
                workspaceRoot.resolve("intermediates/intermediates-manifest.json"),
                indexWriter.manifestJson(workspaceRoot, intermediates, layout));
    }

    private String irDump(Map<String, IrMethod> methods, String owner) {
        StringBuilder builder = new StringBuilder();
        methods.values().stream()
                .filter(method -> method.owner().equals(owner))
                .forEach(method -> builder.append(method).append('\n'));
        return builder.toString();
    }

}
