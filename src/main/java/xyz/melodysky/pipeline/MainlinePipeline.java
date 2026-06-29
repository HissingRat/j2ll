package xyz.melodysky.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.CallGraphBuilder;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.analysis.hierarchy.ClassHierarchyBuilder;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.analysis.reflection.StaticReflectionResolver;
import xyz.melodysky.analysis.runtime.RuntimeAnalysisPipeline;
import xyz.melodysky.analysis.runtime.RuntimeTypeResult;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.backend.llvm.protection.LlvmCallIndirectionPass;
import xyz.melodysky.backend.llvm.protection.LlvmCallIndirectionResult;
import xyz.melodysky.backend.llvm.protection.LlvmProtectionPipeline;
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
import xyz.melodysky.frontend.classfile.JarClassFileSource;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.ir.model.IrClass;
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
import xyz.melodysky.packaging.FallbackBlobInput;
import xyz.melodysky.packaging.FallbackBlobPlanner;
import xyz.melodysky.packaging.JarPreservationReport;
import xyz.melodysky.packaging.JarRepackager;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeLoaderClassGenerator;
import xyz.melodysky.packaging.NativeEmbeddedFallbackBlob;
import xyz.melodysky.packaging.NativeOriginalClassRewriter;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.packaging.RuntimeSupportEntries;
import xyz.melodysky.packaging.SignatureActionReport;
import xyz.melodysky.packaging.JarSignatureResigner;
import xyz.melodysky.packaging.JarSignatureResignResult;
import xyz.melodysky.packaging.J2llMetadataEntries;
import xyz.melodysky.packaging.SignatureResignPreflight;
import xyz.melodysky.packaging.SignatureResignPreflightResult;
import xyz.melodysky.report.EmbeddedLibraryReport;
import xyz.melodysky.report.ArtifactAudit;
import xyz.melodysky.report.ArtifactAuditResult;
import xyz.melodysky.report.ArtifactAuditReportWriter;
import xyz.melodysky.report.FallbackSiteReport;
import xyz.melodysky.report.FailureReportWriter;
import xyz.melodysky.report.FrontendSkipReportWriter;
import xyz.melodysky.report.KnownBlockersWriter;
import xyz.melodysky.report.LoweringReportMethod;
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
import xyz.melodysky.runtime.jni.JniTypeMapper;
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
import xyz.melodysky.toolchain.NativeLibraryArtifact;
import xyz.melodysky.toolchain.ZigNativeBuildResult;
import xyz.melodysky.toolchain.ZigNativeLibraryBuilder;
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
    private final BytecodeToSsaLowerer ssaLowerer = new BytecodeToSsaLowerer();
    private final OptimizationPipeline optimizationPipeline = OptimizationPipeline.defaultPipeline();
    private final ProtectionPipeline protectionPipeline = ProtectionPipeline.defaultPipeline();
    private final LlvmProtectionPipeline llvmProtectionPipeline = LlvmProtectionPipeline.defaultPipeline();
    private final LlvmTextEmitter llvmEmitter = new LlvmTextEmitter();
    private final MethodRewritePlanner rewritePlanner = new MethodRewritePlanner();
    private final NativeOriginalClassRewriter classRewriter = new NativeOriginalClassRewriter();
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
        DiagnosticBag diagnostics = new DiagnosticBag();
        createWorkspace(workspaceRoot);
        JarRepackager repackager = new JarRepackager();
        JarPreservationReport preservationReport = repackager.inspectPreservation(config.jarFile());
        SignatureActionReport signatureAction = repackager.inspectSignature(config.jarFile(), config.signaturePolicy());
        if (signatureAction.signedInput() && signatureAction.action().equals("fail")) {
            diagnostics.add(Diagnostic.error(
                            DiagnosticStage.PACKAGING,
                            xyz.melodysky.diagnostic.DiagnosticCode.SIGNED_INPUT_REJECTED,
                            signatureAction.reason())
                    .withDecision("failed"));
            return failed(config, workspaceRoot, diagnostics, preservationReport, signatureAction);
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
                return failed(config, workspaceRoot, diagnostics, preservationReport, signatureAction);
            }
        }

        var parseResult = parser.parseAll(new JarClassFileSource(config.jarFile()));
        diagnostics.addAll(ProtectionAvailabilityReporter.currentImplementation().report(config.protection()));
        diagnostics.addAll(parseResult.diagnostics());
        if (parseResult.artifact().isEmpty()) {
            return failed(config, workspaceRoot, diagnostics);
        }
        ParsedProgram program = parseResult.artifact().map(ClassParseResult::program).orElseThrow();

        SelectorMatchResult selection = selectorMatcher.expand(program, config.whiteList(), config.blackList());
        diagnostics.addAll(selection.diagnostics());

        var hierarchyResult = new ClassHierarchyBuilder().build(program, config.worldModel());
        diagnostics.addAll(hierarchyResult.diagnostics());
        ClassHierarchy hierarchy = hierarchyResult.artifact().orElseThrow();
        var metadataResult = new RuntimeMetadataIndexBuilder().build(program);
        diagnostics.addAll(metadataResult.diagnostics());
        RuntimeMetadataIndex metadataIndex = metadataResult.artifact().orElseThrow();
        ReflectionPlan reflectionPlan = new StaticReflectionResolver().resolve(program, metadataIndex);
        CallGraph callGraph = new CallGraphBuilder().buildCha(program, hierarchy, metadataIndex);
        RuntimeTypeResult runtimeTypes = new RuntimeAnalysisPipeline().analyze(program);

        Map<String, MethodCfgResult> cfgByMethod = new LinkedHashMap<>();
        ArrayList<SsaMethodResult> ssaResults = new ArrayList<>();
        Map<String, IrMethod> rawIr = new LinkedHashMap<>();
        Map<String, IrMethod> optimizedIr = new LinkedHashMap<>();
        Map<String, IrMethod> protectedIr = new LinkedHashMap<>();
        ArrayList<ProtectionPassReport> protectionReports = new ArrayList<>();
        long seed = seedAsLong(config.protection().seed());
        boolean llvmNameObfuscationEnabled = config.protection().enabled()
                && config.protection().llvm().enabled()
                && config.protection().llvm().nameObfuscation().enabled();
        boolean llvmCallIndirectionEnabled = config.protection().enabled()
                && config.protection().llvm().enabled()
                && config.protection().llvm().indirectCalls().enabled();
        LlvmNameMangler llvmNameMangler = llvmNameObfuscationEnabled
                ? LlvmNameMangler.obfuscating(seed)
                : new LlvmNameMangler();
        LlvmModuleLowerer llvmLowerer = new LlvmModuleLowerer(llvmNameMangler);
        for (ParsedMethod method : selection.requestedMethods()) {
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
            var optimizedResult = optimizationPipeline.run(raw, PassContext.empty());
            diagnostics.addAll(optimizedResult.diagnostics());
            IrMethod optimized = optimizedResult.artifact().orElse(raw);
            optimizedIr.put(method.methodKey(), optimized);
            var protectionResult = protectionPipeline.runDetailed(
                    optimized,
                    xyz.melodysky.ir.pass.protection.ProtectionConfig.fromResolved(config.protection(), seed));
            diagnostics.addAll(protectionResult.diagnostics());
            protectionReports.addAll(protectionResult.reports());
            protectedIr.put(method.methodKey(), protectionResult.method());
        }

        List<MethodRewriteDecision> rewriteDecisions = rewriteDecisions(program, selection.requestedMethods(), ssaResults);
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(rewriteDecisions);
        NativeImplementationPlan implementationPlan = new NativeImplementationPlanner(llvmNameMangler).plan(
                registrationPlan,
                rewriteDecisions,
                protectedIr,
                nativeEmbeddedFallbackMethodKeys(ssaResults),
                program.classes().stream()
                        .flatMap(parsedClass -> parsedClass.methods().stream())
                        .map(ParsedMethod::methodKey)
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        implementationPlan = protectTemplateStringConstants(
                implementationPlan,
                diagnostics,
                protectionReports,
                config,
                seed);
        Set<String> directCallTargets = implementationPlan.llvmImplementations().stream()
                .flatMap(implementation -> implementation.directCallTargets().stream())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Set<String> staticCallTargets = implementationPlan.llvmImplementations().stream()
                .flatMap(implementation -> implementation.staticCallKeys().stream())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

        Map<String, List<IrMethod>> protectedMethodsByClass = groupMethodsByClass(protectedIr.values().stream().toList());
        Map<String, LlvmModule> llvmModules = new LinkedHashMap<>();
        Map<String, String> llvmTextByClass = new LinkedHashMap<>();
        for (ParsedClass parsedClass : program.classes()) {
            List<IrMethod> methods = protectedMethodsByClass.getOrDefault(parsedClass.internalName(), List.of());
            if (methods.isEmpty()) {
                continue;
            }
            LlvmModule module = llvmLowerer.lowerClass(new IrClass(parsedClass.internalName(), methods),
                    xyz.melodysky.backend.llvm.model.LlvmLinkage.EXTERNAL,
                    xyz.melodysky.backend.llvm.model.LlvmVisibility.HIDDEN,
                    directCallTargets,
                    staticCallTargets);
            LlvmModule protectedModule = llvmProtectionPipeline.run(
                    module,
                    xyz.melodysky.backend.llvm.protection.LlvmProtectionConfig.disabled(seed));
            LlvmCallIndirectionResult callIndirectionResult = new LlvmCallIndirectionPass().run(
                    protectedModule,
                    llvmCallIndirectionEnabled
                            ? xyz.melodysky.backend.llvm.protection.LlvmProtectionConfig.enabled(seed)
                            : xyz.melodysky.backend.llvm.protection.LlvmProtectionConfig.disabled(seed));
            protectedModule = callIndirectionResult.module();
            protectionReports.add(callIndirectionReport(
                    llvmCallIndirectionEnabled,
                    methods,
                    callIndirectionResult,
                    llvmNameMangler,
                    seed));
            if (llvmNameObfuscationEnabled) {
                protectionReports.add(new ProtectionPassReport(
                        "LLVM_NAME_OBFUSCATION",
                        "LLVM",
                        "RAN",
                        "OK",
                        methods.stream().map(IrMethod::methodKey).toList(),
                        protectedModule.functions().stream().map(function -> function.name()).toList(),
                        Long.toString(seed)));
            } else {
                protectionReports.add(new ProtectionPassReport(
                        "LLVM_NAME_OBFUSCATION",
                        "LLVM",
                        "SKIPPED",
                        "PROTECTION_PASS_DISABLED",
                        methods.stream().map(IrMethod::methodKey).toList(),
                        List.of(),
                        Long.toString(seed)));
            }
            llvmModules.put(parsedClass.internalName(), protectedModule);
            llvmTextByClass.put(parsedClass.internalName(), llvmEmitter.emit(protectedModule));
        }

        Map<String, LoweringStatus> statuses = methodStatuses(program, selection, ssaResults);
        IntermediateArtifactLayout layout = new IntermediateArtifactLayoutPlanner().plan(classArtifactInputs(program, statuses));
        writeIntermediates(
                workspaceRoot,
                config.intermediates(),
                layout,
                cfgByMethod,
                rawIr,
                optimizedIr,
                protectedIr,
                llvmTextByClass,
                callGraph,
                runtimeTypes,
                metadataIndex,
                reflectionPlan);

        NativeBuildPlan nativeBuildPlan = new NativeBuildPlanner().plan(
                workspaceRoot,
                config.libraryName() == null ? "j2ll_" + seedHash(config.protection().seed()).substring(0, 16) : config.libraryName(),
                config.targets());
        diagnostics.addAll(targetPreflightDiagnostics(nativeBuildPlan));
        Optional<ZigNativeBuildResult> nativeBuildResult = new ZigNativeLibraryBuilder(
                llvmNameMangler,
                llvmCallIndirectionEnabled,
                seed).build(
                workspaceRoot,
                config.embeddedLibraryDirectory(),
                nativeBuildPlan,
                implementationPlan,
                protectedIr);
        String loaderInternalName = nativeBuildResult
                .map(ignored -> loaderInternalName(config))
                .orElse(null);
        Path outputJar = workspaceRoot.resolve("output").resolve(config.jarFile().getFileName());
        if (!diagnostics.hasErrors()) {
            Map<String, byte[]> rewrittenEntries = rewriteClasses(
                    program,
                    rewriteDecisions,
                    implementationPlan,
                    diagnostics,
                    loaderInternalName);
            Map<String, byte[]> addedEntries = addedJarEntries(config, nativeBuildResult, loaderInternalName);
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

        List<SymbolAuditReportWriter.LibraryAuditReport> symbolAudits = symbolAudits(nativeBuildPlan, nativeBuildResult);
        List<EmbeddedLibraryReport> embeddedLibraryReports = embeddedLibraries(config, nativeBuildResult);
        Files.writeString(workspaceRoot.resolve("reports/packaging-report.json"), new PackagingReportWriter().packagingJson(
                workspaceRoot.relativize(outputJar),
                config.signaturePolicy(),
                nativeBuildResult.isPresent() ? List.of(loaderInternalName(config)) : List.of(),
                rewriteDecisions,
                embeddedLibraryReports,
                implementationPlan.registrationPlan().entries(),
                nativeBuildResult.map(ZigNativeBuildResult::exportedSymbols).orElse(List.of()),
                nativeBuildResult.orElse(null),
                nativeBuildPlan,
                fallbackBlobs(layout, ssaResults),
                preservationReport,
                signatureAction));
        ArtifactAuditResult artifactAuditResult = artifactAudit.skipped(
                "FINAL_ARTIFACT_NOT_WRITTEN",
                "pipeline did not reach final output JAR artifact audit");
        if (!diagnostics.hasErrors()) {
            artifactAuditResult = artifactAudit.audit(
                    workspaceRoot,
                    outputJar,
                    config.embeddedLibraryDirectory(),
                    embeddedLibraryReports,
                    nativeBuildResult.map(ZigNativeBuildResult::exportedSymbols).orElse(List.of()),
                    sensitivePlaintextFacts(protectionReports, implementationPlan));
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
                embeddedLibraryReports,
                artifactAuditResult,
                protectionReports,
                preservationReport,
                signatureAction);

        return new MainlinePipelineResult(
                workspaceRoot,
                outputJar,
                diagnostics.diagnostics(),
                nativeBuildPlan,
                registrationPlan,
                !diagnostics.hasErrors());
    }

    private void createWorkspace(Path workspaceRoot) throws IOException {
        Files.createDirectories(workspaceRoot.resolve("output"));
        Files.createDirectories(workspaceRoot.resolve("reports"));
        Files.createDirectories(workspaceRoot.resolve("native"));
        Files.createDirectories(workspaceRoot.resolve("intermediates/classes"));
        Files.createDirectories(workspaceRoot.resolve("intermediates/runtime"));
        Files.createDirectories(workspaceRoot.resolve("intermediates/dumps"));
        Files.createDirectories(workspaceRoot.resolve("logs"));
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
                        resolved.intensity(),
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
                    Optional.of(protectionResult.method())));
        }
        return new NativeImplementationPlan(implementations);
    }

    private ProtectionPassReport callIndirectionReport(
            boolean enabled,
            List<IrMethod> methods,
            LlvmCallIndirectionResult result,
            LlvmNameMangler llvmNameMangler,
            long seed) {
        if (!enabled) {
            return new ProtectionPassReport(
                    "CALL_INDIRECTION",
                    "LLVM",
                    "SKIPPED",
                    "PROTECTION_PASS_DISABLED",
                    methods.stream().map(IrMethod::methodKey).toList(),
                    List.of(),
                    Long.toString(seed));
        }
        List<String> affectedMethods = methods.stream()
                .filter(method -> result.affectedFunctions().contains(llvmNameMangler.functionName(method)))
                .map(IrMethod::methodKey)
                .sorted()
                .toList();
        return new ProtectionPassReport(
                "CALL_INDIRECTION",
                "LLVM",
                result.changed() ? "RAN" : "SKIPPED",
                result.reasonCode(),
                affectedMethods,
                result.dispatcherSymbols(),
                Long.toString(seed));
    }

    private MainlinePipelineResult failed(ResolvedConfig config, Path workspaceRoot, DiagnosticBag diagnostics)
            throws IOException {
        return failed(
                config,
                workspaceRoot,
                diagnostics,
                JarPreservationReport.empty(),
                SignatureActionReport.none(false));
    }

    private MainlinePipelineResult failed(
            ResolvedConfig config,
            Path workspaceRoot,
            DiagnosticBag diagnostics,
            JarPreservationReport preservationReport,
            SignatureActionReport signatureAction)
            throws IOException {
        NativeBuildPlan buildPlan = new NativeBuildPlanner().plan(workspaceRoot, "j2ll_failed", config.targets());
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlan(List.of());
        Path outputJar = workspaceRoot.resolve("output").resolve(config.jarFile().getFileName());
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
                List.of(),
                preservationReport,
                signatureAction));
        Files.writeString(workspaceRoot.resolve("reports/artifact-audit.json"),
                new ArtifactAuditReportWriter().json(artifactAudit.skipped(
                        "FINAL_ARTIFACT_NOT_WRITTEN",
                        "pipeline failed before final output JAR was written")));
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
                .filter(result -> result.status() == LoweringStatus.LOWERED
                        || result.status() == LoweringStatus.HALF_LOWERED)
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
            DiagnosticBag diagnostics,
            String loaderInternalName) {
        Set<String> implementedMethodKeys = implementationPlan.implementations().stream()
                .map(NativeMethodImplementation::methodKey)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<String, List<MethodRewriteDecision>> byClass = new HashMap<>();
        decisions.stream()
                .filter(decision -> implementedMethodKeys.contains(decision.method().methodKey()))
                .filter(decision -> decision.strategy() == MethodRewriteStrategy.NATIVE_ORIGINAL
                        || decision.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB
                        || decision.strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB)
                .forEach(decision -> byClass.computeIfAbsent(decision.method().owner(), ignored -> new ArrayList<>())
                        .add(decision));
        Map<String, byte[]> rewritten = new LinkedHashMap<>();
        for (ParsedClass parsedClass : program.classes()) {
            List<MethodRewriteDecision> classDecisions = byClass.getOrDefault(parsedClass.internalName(), List.of());
            if (classDecisions.isEmpty()) {
                continue;
            }
            ClassRewriteResult result = loaderInternalName == null
                    ? classRewriter.rewrite(parsedClass, classDecisions)
                    : classRewriter.rewrite(parsedClass, classDecisions, loaderInternalName);
            diagnostics.addAll(result.diagnostics());
            if (!result.applied().isEmpty()) {
                rewritten.put(parsedClass.sourceEntry(), result.classBytes());
            }
        }
        return rewritten;
    }

    private Map<String, byte[]> addedJarEntries(
            ResolvedConfig config,
            Optional<ZigNativeBuildResult> nativeBuildResult,
            String loaderInternalName) throws IOException {
        Map<String, byte[]> added = new LinkedHashMap<>();
        if (nativeBuildResult.isPresent() && loaderInternalName != null) {
            ZigNativeBuildResult result = nativeBuildResult.orElseThrow();
            added.put(
                    loaderInternalName + ".class",
                    new NativeLoaderClassGenerator().generate(loaderInternalName, result.artifacts()));
            added.putAll(new RuntimeSupportEntries().loaderSupportEntries());
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
            List<EmbeddedLibraryReport> embeddedLibraryReports,
            ArtifactAuditResult artifactAuditResult,
            List<ProtectionPassReport> protectionReports,
            JarPreservationReport preservationReport,
            SignatureActionReport signatureAction) throws IOException {
        Path reports = workspaceRoot.resolve("reports");
        NativeRegistrationPlan implementedRegistrationPlan = implementationPlan.registrationPlan();
        Files.writeString(workspaceRoot.resolve("config.resolved.json"), new ResolvedConfigReportWriter().json(config));
        Files.writeString(reports.resolve("diagnostics.json"), new ReportJsonWriter().diagnosticsJson(diagnostics.diagnostics()));
        if (diagnostics.hasErrors()) {
            Files.writeString(reports.resolve("failure-report.json"),
                    new FailureReportWriter().json(diagnostics.diagnostics(), false));
        }
        Files.writeString(reports.resolve("frontend-skip-report.json"), new FrontendSkipReportWriter().json(ssaResults));
        Files.writeString(reports.resolve("lowering-report.json"), new ReportJsonWriter().loweringJson(
                loweringReportMethods(
                        program,
                        layout,
                        ssaResults,
                        rewriteDecisions,
                        implementedRegistrationPlan,
                        implementationPlan,
                        defaultInterfaceConflictSignatures(program)),
                selection.notApplicable(),
                selection.excluded()));
        Files.writeString(reports.resolve("packaging-report.json"), new PackagingReportWriter().packagingJson(
                workspaceRoot.relativize(outputJar),
                config.signaturePolicy(),
                nativeBuildResult.isPresent() ? List.of(loaderInternalName(config)) : List.of(),
                rewriteDecisions,
                embeddedLibraryReports,
                implementedRegistrationPlan.entries(),
                nativeBuildResult.map(ZigNativeBuildResult::exportedSymbols).orElse(List.of()),
                nativeBuildResult.orElse(null),
                nativeBuildPlan,
                fallbackBlobs(layout, ssaResults),
                preservationReport,
                signatureAction));
        Files.writeString(reports.resolve("artifact-audit.json"), new ArtifactAuditReportWriter().json(artifactAuditResult));
        Files.writeString(reports.resolve("protection-report.json"),
                new ProtectionReportWriter().json(
                        config.protection().seed(),
                        classifiedProtectionReports(protectionReports, implementationPlan)));
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
        return protectionReports.stream()
                .flatMap(report -> report.sensitivePlaintextFacts().stream())
                .map(fact -> classifiedSensitiveFact(fact, llvmNativeMethods, implementationsByMethod))
                .sorted(java.util.Comparator
                        .comparing(SensitivePlaintextFact::literalHash)
                        .thenComparing(SensitivePlaintextFact::sourceMethod)
                        .thenComparing(SensitivePlaintextFact::pathKind)
                        .thenComparing(SensitivePlaintextFact::gateMode)
                        .thenComparing(SensitivePlaintextFact::promotionReason))
                .toList();
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
                                .map(fact -> classifiedSensitiveFact(fact, llvmNativeMethods, implementationsByMethod))
                                .toList()))
                .toList();
    }

    private SensitivePlaintextFact classifiedSensitiveFact(
            SensitivePlaintextFact fact,
            Set<String> llvmNativeMethods,
            Map<String, NativeMethodImplementation> implementationsByMethod) {
        if (!isStableBlockingPlaintext(fact.plaintext())) {
            NativeMethodImplementation implementation = implementationsByMethod.get(fact.sourceMethod());
            return fact.withAuditClassification(
                    implementation == null ? "HELPER_PATH" : implementation.path().name(),
                    "observedOnly",
                    "PLAINTEXT_LITERAL_TOO_SHORT_FOR_BLOCKING_GATE",
                    "metadataSensitiveObservedOnly");
        }
        if (llvmNativeMethods.contains(fact.sourceMethod())) {
            return fact.withAuditClassification(
                    "LLVM_NATIVE_PATH",
                    "blocking",
                    "LLVM_NATIVE_PATH_CONNECTED_SURFACE",
                    "llvmNativeSurface");
        }
        NativeMethodImplementation implementation = implementationsByMethod.get(fact.sourceMethod());
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
                        .anyMatch(symbol -> symbol.equals("j2ll_rt_string_constant"))) {
            return fact.withAuditClassification(
                    "HELPER_PATH_STABLE_GENERATED_C_SURFACE",
                    "blocking",
                    "STRING_CONCAT_CONSTANT_CARRIER_STABLE_SURFACE",
                    "stableGeneratedCSurface");
        }
        if (implementation != null
                && implementation.reasonCode().equals("NATIVE_EMBEDDED_CLASS_BLOB_FALLBACK")) {
            return fact.withAuditClassification(
                    "FALLBACK_BLOB_COMPLEX",
                    "observedOnly",
                    "FALLBACK_BLOB_SURFACE_NOT_FULLY_AUDITED",
                    "fallbackComplexObservedOnly");
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

    private List<LoweringReportMethod> loweringReportMethods(
            ParsedProgram program,
            IntermediateArtifactLayout layout,
            List<SsaMethodResult> ssaResults,
            List<MethodRewriteDecision> rewriteDecisions,
            NativeRegistrationPlan registrationPlan,
            NativeImplementationPlan implementationPlan,
            Set<String> defaultInterfaceConflictSignatures) {
        Set<String> defaultInterfaceMethodKeys = defaultInterfaceMethodKeys(program);
        ArrayList<LoweringReportMethod> methods = new ArrayList<>();
        for (SsaMethodResult result : ssaResults) {
            ParsedMethod source = result.sourceMethod();
            MethodArtifact methodArtifact = methodArtifact(layout, source.methodKey());
            MethodRewriteDecision rewrite = rewriteDecisions.stream()
                    .filter(decision -> decision.method().methodKey().equals(source.methodKey()))
                    .findFirst()
                    .orElse(null);
            NativeRegistrationEntry registration = registrationFor(source, rewrite, registrationPlan);
            methods.add(new LoweringReportMethod(
                    source.owner(),
                    source.name(),
                    source.descriptor(),
                    methodArtifact.methodId(),
                    result.status(),
                    rewrite == null ? null : rewrite.strategy(),
                    source.accessFlags().names(),
                    compilerFlags(source),
                    registration == null ? null : registration.nativeSymbol(),
                    registration == null ? null : registration.registrationOwner(),
                    implementationPlan.implementationFor(source.methodKey())
                            .map(implementation -> implementation.path().wireName())
                            .orElse(null),
                    helperBackedSites(
                            result,
                            source,
                            registration,
                            implementationPlan.implementationFor(source.methodKey()),
                            defaultInterfaceConflictSignatures,
                            defaultInterfaceMethodKeys),
                    fallbackSites(result),
                    result.reasonCode(),
                    result.reason()));
        }
        return methods;
    }

    private List<xyz.melodysky.report.HelperBackedSiteReport> helperBackedSites(
            SsaMethodResult result,
            ParsedMethod source,
            NativeRegistrationEntry registration,
            Optional<NativeMethodImplementation> implementation,
            Set<String> defaultInterfaceConflictSignatures,
            Set<String> defaultInterfaceMethodKeys) {
        ArrayList<xyz.melodysky.report.HelperBackedSiteReport> sites = new ArrayList<>();
        if (result.irMethod().isEmpty()) {
            return jniAbiSite(source, registration);
        }
        if (source.name().equals("<init>")) {
            sites.add(new xyz.melodysky.report.HelperBackedSiteReport(
                    "constructor:" + source.methodKey(),
                    "CONSTRUCTOR_BODY_HELPER"));
        }
        if (source.name().equals("<clinit>")) {
            sites.add(new xyz.melodysky.report.HelperBackedSiteReport(
                    "classInitializer:" + source.methodKey(),
                    "CLASS_INITIALIZER_BODY_HELPER"));
        }
        if (source.accessFlags().isSynchronized()) {
            sites.add(new xyz.melodysky.report.HelperBackedSiteReport(
                    "synchronizedMethod:" + source.methodKey(),
                    "SYNCHRONIZED_METHOD_HELPER"));
        }
        result.irMethod().orElseThrow().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_RUNTIME_HELPER
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.MONITOR_ENTER
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.MONITOR_EXIT
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.MONITOR_EXIT_ON_EXCEPTION
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CLASS_INIT_GUARD
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CLASS_INIT_BEGIN
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CLASS_INIT_END
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CLASS_INIT_FAILED
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.DIV_I32
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.REM_I32
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.DIV_I64
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.REM_I64
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.I2B
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.I2C
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.I2S
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.F2I
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.F2L
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.D2I
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.D2L
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.LCMP
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.FCMPL
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.FCMPG
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.DCMPL
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.DCMPG
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.VOLATILE_READ_BARRIER
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.VOLATILE_WRITE_BARRIER
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.FINAL_FIELD_PUBLICATION
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.MONITOR_HAPPENS_BEFORE
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.NEW_OBJECT
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.NEW_ARRAY
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.NEW_MULTI_ARRAY
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LENGTH
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LOAD_I32
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LOAD_I64
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LOAD_F32
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LOAD_F64
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LOAD_REF
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_STORE_I32
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_STORE_I64
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_STORE_F32
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_STORE_F64
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_STORE_REF
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CHECKCAST
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.INSTANCEOF
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.GET_STATIC
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.PUT_STATIC
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.GET_FIELD
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.PUT_FIELD
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_STATIC
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_SPECIAL
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_VIRTUAL
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_INTERFACE
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_DYNAMIC)
                .map(instruction -> new xyz.melodysky.report.HelperBackedSiteReport(
                        helperBackedSiteName(instruction, implementation),
                        helperBackedReasonCode(instruction, implementation)))
                .distinct()
                .forEach(sites::add);
        result.irMethod().orElseThrow().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_VIRTUAL
                        || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_INTERFACE)
                .map(instruction -> new xyz.melodysky.report.HelperBackedSiteReport(
                        "dispatch:" + instruction.symbol().orElse(instruction.opcode().name()),
                        "DISPATCH_HELPER"))
                .distinct()
                .forEach(sites::add);
        result.irMethod().orElseThrow().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_INTERFACE)
                .map(instruction -> new xyz.melodysky.report.HelperBackedSiteReport(
                        "defaultInterfaceDispatch:" + instruction.symbol().orElse(instruction.opcode().name()),
                        "DEFAULT_INTERFACE_DISPATCH_HELPER"))
                .distinct()
                .forEach(sites::add);
        result.irMethod().orElseThrow().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_SPECIAL)
                .filter(instruction -> instruction.symbol()
                        .map(defaultInterfaceMethodKeys::contains)
                        .orElse(false))
                .flatMap(instruction -> java.util.stream.Stream.of(
                        new xyz.melodysky.report.HelperBackedSiteReport(
                                "defaultInterfaceSuper:" + instruction.symbol().orElse(instruction.opcode().name()),
                                "UNSUPPORTED_DEFAULT_INTERFACE_SUPER"),
                        new xyz.melodysky.report.HelperBackedSiteReport(
                                "defaultInterfaceSuperFallback:" + instruction.symbol().orElse(instruction.opcode().name()),
                                "DEFAULT_INTERFACE_SUPER_FALLBACK")))
                .distinct()
                .forEach(sites::add);
        result.irMethod().orElseThrow().blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_INTERFACE)
                .filter(instruction -> instruction.symbol()
                        .map(symbol -> defaultInterfaceConflictSignatures.contains(methodSignatureFromKey(symbol)))
                        .orElse(false))
                .flatMap(instruction -> java.util.stream.Stream.of(
                        new xyz.melodysky.report.HelperBackedSiteReport(
                                "defaultInterfaceConflict:" + instruction.symbol().orElse(instruction.opcode().name()),
                                "UNSUPPORTED_DEFAULT_INTERFACE_CONFLICT"),
                        new xyz.melodysky.report.HelperBackedSiteReport(
                                "defaultInterfaceDispatchFallback:" + instruction.symbol().orElse(instruction.opcode().name()),
                                "DEFAULT_INTERFACE_DISPATCH_FALLBACK")))
                .distinct()
                .forEach(sites::add);
        result.irMethod().orElseThrow().blocks().stream()
                .filter(block -> block.terminator().kind() == xyz.melodysky.ir.model.IrTerminatorKind.THROW)
                .map(block -> new xyz.melodysky.report.HelperBackedSiteReport(
                        "exception:" + block.name(),
                        "EXCEPTION_HELPER"))
                .distinct()
                .forEach(sites::add);
        sites.addAll(jniAbiSite(source, registration));
        return sites.stream()
                .distinct()
                .sorted(java.util.Comparator
                        .comparing(xyz.melodysky.report.HelperBackedSiteReport::reasonCode)
                        .thenComparing(xyz.melodysky.report.HelperBackedSiteReport::helper))
                .toList();
    }

    private Set<String> defaultInterfaceConflictSignatures(ParsedProgram program) {
        Map<String, ParsedClass> classes = new HashMap<>();
        for (ParsedClass parsedClass : program.classes()) {
            classes.put(parsedClass.internalName(), parsedClass);
        }
        java.util.LinkedHashSet<String> conflictSignatures = new java.util.LinkedHashSet<>();
        for (ParsedClass parsedClass : program.classes()) {
            if (parsedClass.interfaces().isEmpty()) {
                continue;
            }
            Map<String, java.util.LinkedHashSet<String>> providersBySignature = new LinkedHashMap<>();
            for (String interfaceName : parsedClass.interfaces()) {
                collectDefaultInterfaceProviders(interfaceName, classes, providersBySignature, new java.util.LinkedHashSet<>());
            }
            for (Map.Entry<String, java.util.LinkedHashSet<String>> entry : providersBySignature.entrySet()) {
                if (entry.getValue().size() > 1 && !declaresConcreteMethod(parsedClass, entry.getKey())) {
                    conflictSignatures.add(entry.getKey());
                }
            }
        }
        return Set.copyOf(conflictSignatures);
    }

    private Set<String> defaultInterfaceMethodKeys(ParsedProgram program) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (ParsedClass parsedClass : program.classes()) {
            if (!parsedClass.isInterface()) {
                continue;
            }
            for (ParsedMethod method : parsedClass.methods()) {
                if (isDefaultInterfaceMethod(method)) {
                    keys.add(method.methodKey());
                }
            }
        }
        return Set.copyOf(keys);
    }

    private void collectDefaultInterfaceProviders(
            String interfaceName,
            Map<String, ParsedClass> classes,
            Map<String, java.util.LinkedHashSet<String>> providersBySignature,
            java.util.Set<String> seen) {
        if (!seen.add(interfaceName)) {
            return;
        }
        ParsedClass parsedClass = classes.get(interfaceName);
        if (parsedClass == null) {
            return;
        }
        if (parsedClass.isInterface()) {
            for (ParsedMethod method : parsedClass.methods()) {
                if (isDefaultInterfaceMethod(method)) {
                    providersBySignature
                            .computeIfAbsent(method.name() + "!" + method.descriptor(), ignored -> new java.util.LinkedHashSet<>())
                            .add(parsedClass.internalName());
                }
            }
        }
        for (String parent : parsedClass.interfaces()) {
            collectDefaultInterfaceProviders(parent, classes, providersBySignature, seen);
        }
    }

    private boolean isDefaultInterfaceMethod(ParsedMethod method) {
        return method.hasCode()
                && !method.name().startsWith("<")
                && !method.accessFlags().isAbstract()
                && !method.accessFlags().isStatic()
                && !method.accessFlags().isPrivate();
    }

    private boolean declaresConcreteMethod(ParsedClass parsedClass, String signature) {
        return parsedClass.methods().stream()
                .anyMatch(method -> (method.name() + "!" + method.descriptor()).equals(signature)
                        && method.hasCode()
                        && !method.accessFlags().isAbstract());
    }

    private String methodSignatureFromKey(String methodKey) {
        int hash = methodKey.indexOf('#');
        return hash < 0 ? methodKey : methodKey.substring(hash + 1);
    }

    private NativeRegistrationEntry registrationFor(
            ParsedMethod source,
            MethodRewriteDecision rewrite,
            NativeRegistrationPlan registrationPlan) {
        if (rewrite == null) {
            return null;
        }
        String methodName = rewrite.generatedHelperName().orElse(source.name());
        String descriptor = registeredDescriptor(rewrite);
        return registrationPlan.entries().stream()
                .filter(entry -> entry.registrationOwner().equals(rewrite.registrationOwner()))
                .filter(entry -> entry.methodName().equals(methodName))
                .filter(entry -> entry.descriptor().equals(descriptor))
                .findFirst()
                .orElse(null);
    }

    private String registeredDescriptor(MethodRewriteDecision rewrite) {
        if (rewrite.strategy() == MethodRewriteStrategy.CONSTRUCTOR_STUB) {
            String descriptor = rewrite.method().descriptor();
            int close = descriptor.indexOf(')');
            return "(L" + rewrite.method().owner() + ";" + descriptor.substring(1, close) + ")V";
        }
        if (rewrite.strategy() == MethodRewriteStrategy.CLASS_INITIALIZER_STUB) {
            return "()V";
        }
        return rewrite.method().descriptor();
    }

    private String helperBackedSiteName(
            xyz.melodysky.ir.model.IrInstruction instruction,
            Optional<NativeMethodImplementation> implementation) {
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.GET_STATIC
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.PUT_STATIC
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.GET_FIELD
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.PUT_FIELD) {
            return "field:" + instruction.symbol().orElse(instruction.opcode().name());
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.DIV_I32
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.REM_I32
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.DIV_I64
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.REM_I64) {
            return "arithmetic:" + instruction.opcode().name();
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.I2B
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.I2C
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.I2S
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.F2I
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.F2L
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.D2I
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.D2L
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.LCMP
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.FCMPL
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.FCMPG
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.DCMPL
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.DCMPG) {
            return "numeric:" + instruction.opcode().name();
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.VOLATILE_READ_BARRIER
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.VOLATILE_WRITE_BARRIER
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.FINAL_FIELD_PUBLICATION
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.MONITOR_HAPPENS_BEFORE) {
            return "jmm:" + instruction.opcode().name() + ":" + instruction.symbol().orElse("fence");
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.MONITOR_ENTER
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.MONITOR_EXIT
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.MONITOR_EXIT_ON_EXCEPTION) {
            return "monitor:" + instruction.opcode().name();
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LENGTH
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LOAD_I32
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_STORE_I32
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LOAD_I64
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_STORE_I64
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LOAD_F32
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_STORE_F32
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LOAD_F64
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_STORE_F64
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LOAD_REF
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_STORE_REF) {
            return "array:" + instruction.opcode().name();
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.NEW_OBJECT
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.NEW_ARRAY
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.NEW_MULTI_ARRAY) {
            return "allocation:" + instruction.symbol().orElse(instruction.opcode().name());
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CHECKCAST
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.INSTANCEOF) {
            return "type:" + instruction.symbol().orElse(instruction.opcode().name());
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_STATIC) {
            String target = instruction.symbol().orElse(instruction.opcode().name());
            if (implementation.map(item -> item.directCallTargets().contains(target)).orElse(false)) {
                return "direct:" + target;
            }
            return "call:" + target;
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_SPECIAL
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_VIRTUAL
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_INTERFACE
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_DYNAMIC) {
            String target = instruction.symbol().orElse(instruction.opcode().name());
            if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_SPECIAL
                    && implementation.map(item -> item.directCallTargets().contains(target)).orElse(false)) {
                return "direct:" + target;
            }
            return "call:" + instruction.symbol().orElse(instruction.opcode().name());
        }
        return instruction.symbol().orElse(instruction.opcode().name());
    }

    private String helperBackedReasonCode(
            xyz.melodysky.ir.model.IrInstruction instruction,
            Optional<NativeMethodImplementation> implementation) {
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.GET_STATIC
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.PUT_STATIC
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.GET_FIELD
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.PUT_FIELD) {
            return "FIELD_HELPER";
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.DIV_I32
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.REM_I32
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.DIV_I64
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.REM_I64) {
            return "DIV_REM_EXCEPTION_HELPER";
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.I2B
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.I2C
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.I2S
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.F2I
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.F2L
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.D2I
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.D2L
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.LCMP
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.FCMPL
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.FCMPG
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.DCMPL
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.DCMPG) {
            return "JVM_NUMERIC_HELPER";
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.VOLATILE_READ_BARRIER
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.VOLATILE_WRITE_BARRIER
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.FINAL_FIELD_PUBLICATION
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.MONITOR_HAPPENS_BEFORE) {
            return "JMM_FENCE";
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.MONITOR_ENTER
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.MONITOR_EXIT
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.MONITOR_EXIT_ON_EXCEPTION) {
            return "MONITOR_HELPER";
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LENGTH
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LOAD_I32
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_STORE_I32
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LOAD_I64
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_STORE_I64
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LOAD_F32
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_STORE_F32
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LOAD_F64
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_STORE_F64
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_LOAD_REF
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.ARRAY_STORE_REF) {
            return "ARRAY_HELPER";
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.NEW_OBJECT
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.NEW_ARRAY) {
            return "ALLOCATION_HELPER";
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.NEW_MULTI_ARRAY) {
            return "UNSUPPORTED_MULTI_ARRAY_ALLOCATION";
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CHECKCAST
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.INSTANCEOF) {
            return "TYPE_HELPER";
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_STATIC) {
            String target = instruction.symbol().orElse("");
            if (implementation.map(item -> item.directCallTargets().contains(target)).orElse(false)) {
                return "DIRECT_LLVM_CALL";
            }
            if (isJdkCollectionCall(target)) {
                return "JDK_COLLECTION_HELPER";
            }
            if (isThrowableCall(target)) {
                return "THROWABLE_HELPER";
            }
            if (isThreadCall(target)) {
                return "THREAD_HELPER";
            }
            if (isWaitNotifyCall(target)) {
                return "WAIT_NOTIFY_FALLBACK";
            }
            return "JVM_CALL_HELPER";
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_SPECIAL) {
            String target = instruction.symbol().orElse("");
            if (implementation.map(item -> item.directCallTargets().contains(target)).orElse(false)) {
                return "DIRECT_LLVM_CALL";
            }
            if (isJdkCollectionCall(target)) {
                return "JDK_COLLECTION_HELPER";
            }
            if (isThrowableCall(target)) {
                return "THROWABLE_HELPER";
            }
            if (isThreadCall(target)) {
                return "THREAD_HELPER";
            }
            if (isWaitNotifyCall(target)) {
                return "WAIT_NOTIFY_FALLBACK";
            }
            if (instruction.symbol().map(symbol -> symbol.contains("#<init>!")).orElse(false)) {
                return "CONSTRUCTOR_CALL_HELPER";
            }
            return "JVM_CALL_HELPER";
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_VIRTUAL
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_INTERFACE
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_DYNAMIC) {
            if (isJdkCollectionCall(instruction.symbol().orElse(""))) {
                return "JDK_COLLECTION_HELPER";
            }
            if (isThrowableCall(instruction.symbol().orElse(""))) {
                return "THROWABLE_HELPER";
            }
            if (isThreadCall(instruction.symbol().orElse(""))) {
                return "THREAD_HELPER";
            }
            if (isWaitNotifyCall(instruction.symbol().orElse(""))) {
                return "WAIT_NOTIFY_FALLBACK";
            }
            return "DEFERRED_DISPATCH_HELPER";
        }
        return helperBackedReasonCode(instruction.symbol().orElse(instruction.opcode().name()));
    }

    private boolean isJdkCollectionCall(String target) {
        return target.contains("java/util/ArrayList#")
                || target.contains("java/util/HashMap#")
                || target.contains("java/util/Arrays#")
                || target.contains("java/util/Collections#")
                || target.contains("java/util/Optional#")
                || target.contains("java/lang/String#format");
    }

    private boolean isThrowableCall(String target) {
        return target.contains("java/lang/Throwable#")
                || target.contains("java/lang/RuntimeException#")
                || target.contains("java/lang/IllegalArgumentException#");
    }

    private boolean isThreadCall(String target) {
        return target.contains("java/lang/Thread#<init>!")
                || target.contains("java/lang/Thread#start!")
                || target.contains("java/lang/Thread#join!");
    }

    private boolean isWaitNotifyCall(String target) {
        return target.contains("java/lang/Object#wait!")
                || target.contains("java/lang/Object#notify!");
    }

    private List<xyz.melodysky.report.HelperBackedSiteReport> jniAbiSite(
            ParsedMethod source,
            NativeRegistrationEntry registration) {
        if (registration == null) {
            return List.of();
        }
        String prototype = new JniTypeMapper()
                .methodDescriptor(source.owner(), source.name(), source.descriptor(), source.accessFlags().isStatic())
                .cPrototype(registration.nativeSymbol());
        return List.of(new xyz.melodysky.report.HelperBackedSiteReport(
                "jni:" + prototype,
                "JNI_ABI_REGISTER_NATIVES"));
    }

    private String helperBackedReasonCode(String helper) {
        if (helper.startsWith("j2ll_rt_var_handle_")) {
            return "VARHANDLE_HELPER";
        }
        if (helper.startsWith("j2ll_rt_string_constant")) {
            return "STRING_CONCAT_CONSTANTS_HELPER";
        }
        if (helper.startsWith("j2ll_rt_lambda_new")) {
            return "LAMBDA_METAFACTORY_HELPER";
        }
        if (helper.startsWith("j2ll_rt_unsafe_")) {
            return "UNSAFE_HELPER";
        }
        if (helper.equals("j2ll_rt_string_length")
                || helper.equals("j2ll_rt_string_is_empty")
                || helper.equals("j2ll_rt_string_char_at")
                || helper.equals("j2ll_rt_string_equals")
                || helper.equals("j2ll_rt_string_starts_with")
                || helper.equals("j2ll_rt_string_ends_with")
                || helper.equals("j2ll_rt_string_substring")
                || helper.equals("j2ll_rt_string_substring_range")) {
            return "STRING_HELPER";
        }
        if (helper.startsWith("j2ll_rt_string_builder_")) {
            return "STRING_BUILDER_HELPER";
        }
        if (helper.equals("j2ll_rt_system_arraycopy")) {
            return "ARRAYCOPY_HELPER";
        }
        if (helper.equals("j2ll_rt_class_for_name_static")
                || helper.startsWith("j2ll_rt_get_declared_field")) {
            return "REFLECTION_HELPER";
        }
        if (helper.startsWith("j2ll_rt_reflect_field_")) {
            return "REFLECTION_FIELD_HELPER";
        }
        if (helper.startsWith("j2ll_rt_get_declared_method")
                || helper.startsWith("j2ll_rt_reflect_invoke")) {
            return "REFLECTION_METHOD_HELPER";
        }
        if (helper.startsWith("j2ll_rt_get_declared_constructor")
                || helper.startsWith("j2ll_rt_reflect_new_instance")) {
            return "REFLECTION_CONSTRUCTOR_HELPER";
        }
        if (helper.startsWith("j2ll_rt_reflect_set_accessible")) {
            return "REFLECTION_ACCESSIBLE_HELPER";
        }
        if (helper.startsWith("j2ll_rt_math_")
                || helper.startsWith("j2ll_rt_integer_")
                || helper.startsWith("j2ll_rt_long_")
                || helper.startsWith("j2ll_rt_boolean_")
                || helper.startsWith("j2ll_rt_double_")
                || helper.startsWith("j2ll_rt_objects_")) {
            return "JDK_INTRINSIC_HELPER";
        }
        if (helper.startsWith("j2ll_rt_class_")
                || helper.startsWith("j2ll_rt_method_handle_")
                || helper.startsWith("j2ll_rt_constant_dynamic")) {
            return "RUNTIME_METADATA_HELPER";
        }
        return "HELPER_BACKED_LOWERING";
    }

    private List<FallbackSiteReport> fallbackSites(SsaMethodResult result) {
        if (result.status() != LoweringStatus.HALF_LOWERED) {
            return List.of();
        }
        return List.of(new FallbackSiteReport(
                -1,
                result.sourceMethod().methodKey(),
                fallbackReasonCode(result),
                "nativeEmbeddedClassBlob"));
    }

    private String fallbackReasonCode(SsaMethodResult result) {
        if (result.irMethod().isPresent()) {
            List<String> symbols = result.irMethod().orElseThrow().blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .flatMap(instruction -> instruction.symbol().stream())
                    .toList();
            if (symbols.stream().anyMatch(symbol -> symbol.contains("java/lang/Class#getDeclaredMethods")
                    || symbol.contains("java/lang/Class#getMethods")
                    || symbol.contains("java/lang/Class#getDeclaredFields")
                    || symbol.contains("java/lang/Class#getFields")
                    || symbol.contains("java/lang/Class#getDeclaredConstructors")
                    || symbol.contains("java/lang/Class#getConstructors"))) {
                return "REFLECTION_UNSUPPORTED_SCAN";
            }
            if (symbols.stream().anyMatch(symbol -> symbol.contains("java/lang/Class#forName")
                    || symbol.contains("java/lang/Class#getDeclared"))) {
                return "REFLECTION_DYNAMIC_FALLBACK";
            }
            if (symbols.stream().anyMatch(symbol -> symbol.contains("#")
                    && symbol.contains("!"))
                    && symbols.stream().anyMatch(symbol -> symbol.contains("default interface super")
                            || symbol.contains("CallNonvirtual"))) {
                return "DEFAULT_INTERFACE_SUPER_FALLBACK";
            }
            if (result.irMethod().orElseThrow().blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .anyMatch(instruction -> instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_DYNAMIC)) {
                if (symbols.stream().anyMatch(symbol -> symbol.contains("altMetafactory"))) {
                    return "ALT_METAFACTORY_FALLBACK";
                }
                if (symbols.stream().anyMatch(symbol -> symbol.contains("LambdaMetafactory"))) {
                    return "LAMBDA_UNSUPPORTED_FALLBACK";
                }
                return "METHOD_HANDLE_CHAIN_FALLBACK";
            }
            if (symbols.stream().anyMatch(symbol -> symbol.contains("MethodHandle")
                    || symbol.contains("method_handle"))) {
                return methodHandleFallbackReason(symbols);
            }
            if (symbols.stream().anyMatch(symbol -> symbol.contains("java/util/ArrayList")
                    || symbol.contains("java/util/HashMap")
                    || symbol.contains("java/util/Arrays")
                    || symbol.contains("java/util/Collections")
                    || symbol.contains("java/util/Optional")
                    || symbol.contains("java/lang/String#format"))) {
                return "JDK_HELPER_FALLBACK";
            }
            if (symbols.stream().anyMatch(this::isWaitNotifyCall)) {
                return "WAIT_NOTIFY_FALLBACK";
            }
            if (symbols.stream().anyMatch(this::isThreadCall)) {
                return "THREAD_HELPER_FALLBACK";
            }
            if (symbols.stream().anyMatch(this::isThrowableCall)) {
                return "THROWABLE_HELPER_FALLBACK";
            }
        }
        String reason = result.reason() == null ? "" : result.reason();
        if (reason.contains("dynamic Class.forName") || reason.contains("dynamic reflection")) {
            return "REFLECTION_DYNAMIC_FALLBACK";
        }
        if (reason.contains("reflection member scan")) {
            return "REFLECTION_UNSUPPORTED_SCAN";
        }
        if (reason.contains("altMetafactory")) {
            return "ALT_METAFACTORY_FALLBACK";
        }
        if (reason.contains("LambdaMetafactory")) {
            return "LAMBDA_UNSUPPORTED_FALLBACK";
        }
        if (reason.contains("MethodHandle") || reason.contains("method handle")) {
            return "METHOD_HANDLE_CHAIN_FALLBACK";
        }
        if (reason.contains("default interface super")) {
            return "DEFAULT_INTERFACE_SUPER_FALLBACK";
        }
        if (reason.contains("JDK_COLLECTION_HELPER_FALLBACK")
                || reason.contains("JDK_ARRAYS_HELPER_FALLBACK")
                || reason.contains("JDK_OPTIONAL_HELPER_FALLBACK")
                || reason.contains("JDK_FORMAT_HELPER_FALLBACK")
                || reason.contains("ArrayList")
                || reason.contains("HashMap")
                || reason.contains("Arrays.")) {
            return "JDK_HELPER_FALLBACK";
        }
        if (reason.contains("WAIT_NOTIFY_FALLBACK")) {
            return "WAIT_NOTIFY_FALLBACK";
        }
        if (reason.contains("THREAD_HELPER_FALLBACK")) {
            return "THREAD_HELPER_FALLBACK";
        }
        if (reason.contains("THROWABLE_HELPER_FALLBACK")) {
            return "THROWABLE_HELPER_FALLBACK";
        }
        return result.reasonCode();
    }

    private String methodHandleFallbackReason(List<String> symbols) {
        if (symbols.stream().anyMatch(symbol -> symbol.contains("java/lang/invoke/MethodHandles#permuteArguments"))) {
            return "METHOD_HANDLE_PERMUTE_FALLBACK";
        }
        if (symbols.stream().anyMatch(symbol -> symbol.contains("java/lang/invoke/MethodHandles#filterArguments")
                || symbol.contains("java/lang/invoke/MethodHandles#filterReturnValue"))) {
            return "METHOD_HANDLE_FILTER_FALLBACK";
        }
        if (symbols.stream().anyMatch(symbol -> symbol.contains("java/lang/invoke/MethodHandles#foldArguments"))) {
            return "METHOD_HANDLE_FOLD_FALLBACK";
        }
        if (symbols.stream().anyMatch(symbol -> symbol.contains("java/lang/invoke/MethodHandle#asCollector")
                || symbol.contains("java/lang/invoke/MethodHandle#asSpreader")
                || symbol.contains("java/lang/invoke/MethodHandles#collectArguments"))) {
            return "METHOD_HANDLE_COLLECTOR_UNSUPPORTED";
        }
        return "METHOD_HANDLE_CHAIN_FALLBACK";
    }

    private List<NativeEmbeddedFallbackBlob> fallbackBlobs(
            IntermediateArtifactLayout layout,
            List<SsaMethodResult> ssaResults) {
        ArrayList<FallbackBlobInput> inputs = new ArrayList<>();
        for (SsaMethodResult result : ssaResults) {
            if (result.status() != LoweringStatus.HALF_LOWERED) {
                continue;
            }
            ParsedMethod method = result.sourceMethod();
            MethodArtifact artifact = methodArtifact(layout, method.methodKey());
            inputs.add(new FallbackBlobInput(
                    artifact.methodId(),
                    method.methodKey(),
                    method.owner(),
                    method.name(),
                    method.descriptor(),
                    method.accessFlags().isStatic(),
                    method.methodNode(),
                    fallbackReasonCode(result)));
        }
        return new FallbackBlobPlanner().plan(inputs);
    }

    private Set<String> nativeEmbeddedFallbackMethodKeys(List<SsaMethodResult> ssaResults) {
        return ssaResults.stream()
                .filter(result -> result.status() == LoweringStatus.HALF_LOWERED)
                .map(result -> result.sourceMethod().methodKey())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private MethodArtifact methodArtifact(IntermediateArtifactLayout layout, String methodKey) {
        for (ClassArtifact classArtifact : layout.classes()) {
            for (MethodArtifact method : layout.methodsFor(classArtifact.internalName())) {
                if ((method.owner() + "#" + method.name() + "!" + method.descriptor()).equals(methodKey)) {
                    return method;
                }
            }
        }
        throw new IllegalStateException("missing method artifact for " + methodKey);
    }

    private List<String> compilerFlags(ParsedMethod method) {
        ArrayList<String> flags = new ArrayList<>();
        if (method.accessFlags().isSynthetic()) {
            flags.add("synthetic");
        }
        if (method.accessFlags().has(xyz.melodysky.jvm.AccessFlags.BRIDGE)) {
            flags.add("bridge");
        }
        return List.copyOf(flags);
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
        ExportList allowlist = new SymbolVisibilityPlanner().defaultLoaderExports();
        return nativeBuildPlan.units().stream()
                .map(unit -> {
                    List<String> actualExports = nativeBuildResult
                            .flatMap(result -> result.artifactFor(unit.target()))
                            .map(NativeLibraryArtifact::exportedSymbols)
                            .orElseGet(() -> allowlist.symbols().stream().map(symbol -> symbol.name()).toList());
                    SymbolAuditResult result = new SymbolAudit().audit(allowlist, actualExports);
                    return new SymbolAuditReportWriter.LibraryAuditReport(unit.target(), unit.outputPath(), result);
                })
                .toList();
    }

    private String loaderInternalName(ResolvedConfig config) {
        return "j2ll/generated/" + seedHash(config.protection().seed()).substring(0, 16) + "/NativeLoader";
    }

    private String seedHash(String seed) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Map<String, LoweringStatus> methodStatuses(
            ParsedProgram program,
            SelectorMatchResult selection,
            List<SsaMethodResult> ssaResults) {
        Map<String, LoweringStatus> statuses = new HashMap<>();
        for (ParsedClass parsedClass : program.classes()) {
            for (ParsedMethod method : parsedClass.methods()) {
                statuses.put(method.methodKey(), LoweringStatus.EXCLUDED);
            }
        }
        selection.notApplicable().forEach(eligibility -> statuses.put(
                eligibility.owner() + "#" + eligibility.name() + "!" + eligibility.descriptor(),
                LoweringStatus.NOT_APPLICABLE));
        selection.excluded().forEach(eligibility -> statuses.put(
                eligibility.owner() + "#" + eligibility.name() + "!" + eligibility.descriptor(),
                LoweringStatus.EXCLUDED));
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
                methods.add(new MethodArtifactInput(
                        method.owner(),
                        method.name(),
                        method.descriptor(),
                        statuses.getOrDefault(method.methodKey(), LoweringStatus.EXCLUDED)));
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

    private Map<String, List<IrMethod>> groupMethodsByClass(List<IrMethod> methods) {
        Map<String, List<IrMethod>> grouped = new LinkedHashMap<>();
        for (IrMethod method : methods) {
            grouped.computeIfAbsent(method.owner(), ignored -> new ArrayList<>()).add(method);
        }
        return grouped;
    }

    private long seedAsLong(String seed) {
        String hex = seed.length() > 16 ? seed.substring(0, 16) : seed;
        try {
            return Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException exception) {
            return Integer.toUnsignedLong(seed.hashCode());
        }
    }
}
