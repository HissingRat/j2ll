package xyz.melodysky.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.packaging.ClassRewriteResult;
import xyz.melodysky.packaging.EmbeddedLibraryLayout;
import xyz.melodysky.packaging.FallbackBlobInput;
import xyz.melodysky.packaging.FallbackBlobPlanner;
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
import xyz.melodysky.report.EmbeddedLibraryReport;
import xyz.melodysky.report.FallbackSiteReport;
import xyz.melodysky.report.FrontendSkipReportWriter;
import xyz.melodysky.report.LoweringReportMethod;
import xyz.melodysky.report.PackagingReportWriter;
import xyz.melodysky.report.ProtectionPassReport;
import xyz.melodysky.report.ProtectionReportWriter;
import xyz.melodysky.report.ReportJsonWriter;
import xyz.melodysky.report.ResolvedConfigReportWriter;
import xyz.melodysky.report.SymbolAuditReportWriter;
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

    public MainlinePipelineResult run(ResolvedConfig config, Path workspaceRoot) throws IOException {
        DiagnosticBag diagnostics = new DiagnosticBag();
        createWorkspace(workspaceRoot);

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

        Map<String, List<IrMethod>> protectedMethodsByClass = groupMethodsByClass(protectedIr.values().stream().toList());
        Map<String, LlvmModule> llvmModules = new LinkedHashMap<>();
        Map<String, String> llvmTextByClass = new LinkedHashMap<>();
        for (ParsedClass parsedClass : program.classes()) {
            List<IrMethod> methods = protectedMethodsByClass.getOrDefault(parsedClass.internalName(), List.of());
            if (methods.isEmpty()) {
                continue;
            }
            LlvmModule module = llvmLowerer.lowerClass(new IrClass(parsedClass.internalName(), methods));
            LlvmModule protectedModule = llvmProtectionPipeline.run(
                    module,
                    xyz.melodysky.backend.llvm.protection.LlvmProtectionConfig.disabled(seed));
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

        List<MethodRewriteDecision> rewriteDecisions = rewriteDecisions(program, selection.requestedMethods());
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(rewriteDecisions);
        NativeImplementationPlan implementationPlan = new NativeImplementationPlanner(llvmNameMangler).plan(
                registrationPlan,
                rewriteDecisions,
                protectedIr);
        NativeBuildPlan nativeBuildPlan = new NativeBuildPlanner().plan(
                workspaceRoot,
                config.libraryName() == null ? "j2ll_" + config.protection().seed() : config.libraryName(),
                config.targets());
        diagnostics.addAll(targetPreflightDiagnostics(nativeBuildPlan));
        Optional<ZigNativeBuildResult> nativeBuildResult = new ZigNativeLibraryBuilder(llvmNameMangler).build(
                workspaceRoot,
                config.embeddedLibraryDirectory(),
                nativeBuildPlan,
                implementationPlan,
                protectedIr);
        String loaderInternalName = nativeBuildResult
                .map(ignored -> loaderInternalName(config))
                .orElse(null);
        Map<String, byte[]> rewrittenEntries = rewriteClasses(
                program,
                rewriteDecisions,
                implementationPlan,
                diagnostics,
                loaderInternalName);
        Map<String, byte[]> addedEntries = addedJarEntries(nativeBuildResult, loaderInternalName);
        Path outputJar = workspaceRoot.resolve("output").resolve(config.jarFile().getFileName());
        new JarRepackager().write(config.jarFile(), outputJar, rewrittenEntries, addedEntries);

        List<SymbolAuditReportWriter.LibraryAuditReport> symbolAudits = symbolAudits(nativeBuildPlan, nativeBuildResult);

        writeReports(
                config,
                workspaceRoot,
                diagnostics,
                selection,
                ssaResults,
                layout,
                rewriteDecisions,
                registrationPlan,
                implementationPlan,
                outputJar,
                symbolAudits,
                nativeBuildPlan,
                nativeBuildResult,
                protectionReports);

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

    private MainlinePipelineResult failed(ResolvedConfig config, Path workspaceRoot, DiagnosticBag diagnostics)
            throws IOException {
        NativeBuildPlan buildPlan = new NativeBuildPlanner().plan(workspaceRoot, "j2ll_failed", config.targets());
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlan(List.of());
        Path outputJar = workspaceRoot.resolve("output").resolve(config.jarFile().getFileName());
        Files.createDirectories(outputJar.getParent());
        Files.writeString(workspaceRoot.resolve("reports/diagnostics.json"),
                new ReportJsonWriter().diagnosticsJson(diagnostics.diagnostics()));
        return new MainlinePipelineResult(workspaceRoot, outputJar, diagnostics.diagnostics(), buildPlan, registrationPlan, false);
    }

    private List<MethodRewriteDecision> rewriteDecisions(ParsedProgram program, List<ParsedMethod> requestedMethods) {
        Map<String, ParsedMethod> requested = new HashMap<>();
        requestedMethods.forEach(method -> requested.put(method.methodKey(), method));
        ArrayList<MethodRewriteDecision> decisions = new ArrayList<>();
        for (ParsedClass parsedClass : program.classes()) {
            for (MethodRewriteDecision decision : rewritePlanner.planClass(parsedClass)) {
                if (requested.containsKey(decision.method().methodKey())) {
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
            Optional<ZigNativeBuildResult> nativeBuildResult,
            String loaderInternalName) throws IOException {
        if (nativeBuildResult.isEmpty() || loaderInternalName == null) {
            return Map.of();
        }
        ZigNativeBuildResult result = nativeBuildResult.orElseThrow();
        Map<String, byte[]> added = new LinkedHashMap<>();
        added.put(
                loaderInternalName + ".class",
                new NativeLoaderClassGenerator().generate(loaderInternalName, result.artifacts()));
        added.putAll(new RuntimeSupportEntries().loaderSupportEntries());
        for (NativeLibraryArtifact artifact : result.artifacts()) {
            added.put(artifact.jarPath(), Files.readAllBytes(artifact.libraryPath()));
        }
        return added;
    }

    private void writeReports(
            ResolvedConfig config,
            Path workspaceRoot,
            DiagnosticBag diagnostics,
            SelectorMatchResult selection,
            List<SsaMethodResult> ssaResults,
            IntermediateArtifactLayout layout,
            List<MethodRewriteDecision> rewriteDecisions,
            NativeRegistrationPlan registrationPlan,
            NativeImplementationPlan implementationPlan,
            Path outputJar,
            List<SymbolAuditReportWriter.LibraryAuditReport> symbolAudits,
            NativeBuildPlan nativeBuildPlan,
            Optional<ZigNativeBuildResult> nativeBuildResult,
            List<ProtectionPassReport> protectionReports) throws IOException {
        Path reports = workspaceRoot.resolve("reports");
        NativeRegistrationPlan implementedRegistrationPlan = implementationPlan.registrationPlan();
        Files.writeString(workspaceRoot.resolve("config.resolved.json"), new ResolvedConfigReportWriter().json(config));
        Files.writeString(reports.resolve("diagnostics.json"), new ReportJsonWriter().diagnosticsJson(diagnostics.diagnostics()));
        Files.writeString(reports.resolve("frontend-skip-report.json"), new FrontendSkipReportWriter().json(ssaResults));
        Files.writeString(reports.resolve("lowering-report.json"), new ReportJsonWriter().loweringJson(
                loweringReportMethods(layout, ssaResults, rewriteDecisions, implementedRegistrationPlan, implementationPlan),
                selection.notApplicable(),
                selection.excluded()));
        Files.writeString(reports.resolve("packaging-report.json"), new PackagingReportWriter().packagingJson(
                workspaceRoot.relativize(outputJar),
                config.signaturePolicy(),
                nativeBuildResult.isPresent() ? List.of(loaderInternalName(config)) : List.of(),
                rewriteDecisions,
                embeddedLibraries(config, nativeBuildResult),
                implementedRegistrationPlan.entries(),
                nativeBuildResult.map(ZigNativeBuildResult::exportedSymbols).orElse(List.of()),
                nativeBuildResult.orElse(null),
                nativeBuildPlan,
                fallbackBlobs(layout, ssaResults)));
        Files.writeString(reports.resolve("protection-report.json"),
                new ProtectionReportWriter().json(config.protection().seed(), protectionReports));
        Files.writeString(reports.resolve("symbol-audit.json"), new SymbolAuditReportWriter().json(symbolAudits));
    }

    private List<Diagnostic> targetPreflightDiagnostics(NativeBuildPlan nativeBuildPlan) {
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        for (var preflight : nativeBuildPlan.targetPreflights()) {
            String message = "Zig target preflight " + preflight.target().directoryName()
                    + " -> " + preflight.status() + ": " + preflight.reason();
            Diagnostic diagnostic = preflight.buildable()
                    ? Diagnostic.info(DiagnosticStage.NATIVE_LINK, ToolchainDiagnostics.ZIG_TARGET_PREFLIGHT, message)
                    : Diagnostic.warning(DiagnosticStage.NATIVE_LINK, ToolchainDiagnostics.ZIG_TARGET_PREFLIGHT, message);
            diagnostics.add(diagnostic.withDecision(preflight.status()));
        }
        return List.copyOf(diagnostics);
    }

    private List<LoweringReportMethod> loweringReportMethods(
            IntermediateArtifactLayout layout,
            List<SsaMethodResult> ssaResults,
            List<MethodRewriteDecision> rewriteDecisions,
            NativeRegistrationPlan registrationPlan,
            NativeImplementationPlan implementationPlan) {
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
                            implementationPlan.implementationFor(source.methodKey())),
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
            Optional<NativeMethodImplementation> implementation) {
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
            return "JVM_CALL_HELPER";
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_SPECIAL) {
            String target = instruction.symbol().orElse("");
            if (implementation.map(item -> item.directCallTargets().contains(target)).orElse(false)) {
                return "DIRECT_LLVM_CALL";
            }
            if (instruction.symbol().map(symbol -> symbol.contains("#<init>!")).orElse(false)) {
                return "CONSTRUCTOR_CALL_HELPER";
            }
            return "JVM_CALL_HELPER";
        }
        if (instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_VIRTUAL
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_INTERFACE
                || instruction.opcode() == xyz.melodysky.ir.model.IrOpcode.CALL_DYNAMIC) {
            return "DEFERRED_DISPATCH_HELPER";
        }
        return helperBackedReasonCode(instruction.symbol().orElse(instruction.opcode().name()));
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
                || helper.startsWith("j2ll_rt_get_declared_")
                || helper.startsWith("j2ll_rt_reflect_")) {
            return "REFLECTION_HELPER";
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
                result.reasonCode(),
                "nativeEmbeddedClassBlob"));
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
                    method.owner()));
        }
        return new FallbackBlobPlanner().plan(inputs);
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
        EmbeddedLibraryLayout layout = new EmbeddedLibraryLayout();
        return config.targets().stream()
                .map(target -> new EmbeddedLibraryReport(
                        target.directoryName(),
                        layout.jarPath(config.embeddedLibraryDirectory(), target),
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
        return "j2ll/generated/" + config.protection().seed() + "/NativeLoader";
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
        Files.writeString(
                workspaceRoot.resolve("intermediates/runtime/runtime-metadata.json"),
                new RuntimeMetadataDumpWriter().write(metadataIndex, reflectionPlan));
        IntermediateArtifactIndexWriter indexWriter = new IntermediateArtifactIndexWriter();
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
                if (cfg != null) {
                    Files.writeString(classDir.resolve("cfg").resolve(method.methodId() + ".cfg.txt"), cfg.toString());
                    Files.writeString(classDir.resolve("cfg").resolve(method.methodId() + ".cfg.json"),
                            "{\"schemaVersion\":1,\"method\":\"" + method.name() + "\"}\n");
                }
            }
            Files.writeString(classDir.resolve("ir/raw.ssa.ir"), irDump(rawIr, classArtifact.internalName()));
            Files.writeString(classDir.resolve("ir/optimized.ssa.ir"), irDump(optimizedIr, classArtifact.internalName()));
            Files.writeString(classDir.resolve("ir/protected.ssa.ir"), irDump(protectedIr, classArtifact.internalName()));
            Files.writeString(classDir.resolve("llvm/class.ll"), llvmTextByClass.getOrDefault(classArtifact.internalName(), ""));
            Files.writeString(classDir.resolve("llvm/protected.class.ll"), llvmTextByClass.getOrDefault(classArtifact.internalName(), ""));
            Files.writeString(classDir.resolve("c/class.c"), "/* planned C wrapper artifact */\n");
            Files.writeString(classDir.resolve("reports/lowering.json"), "{\"schemaVersion\":1}\n");
            Files.writeString(classDir.resolve("reports/protection.json"), "{\"schemaVersion\":1}\n");
        }
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
