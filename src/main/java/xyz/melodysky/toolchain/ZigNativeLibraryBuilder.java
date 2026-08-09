package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.backend.llvm.protection.LlvmProtectionConfig;
import xyz.melodysky.ir.model.BusinessStringSymbolMapper;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.packaging.EmbeddedLibraryLayout;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.progress.NativePreparationProgress;
import xyz.melodysky.progress.NativePreparationStep;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.symbols.NativeSymbolInspector;
import xyz.melodysky.toolchain.symbols.NativeUnwindSectionInspection;
import xyz.melodysky.toolchain.symbols.NativeUnwindSectionInspector;

public final class ZigNativeLibraryBuilder {
    private final HostJniCSourceGenerator sourceGenerator;
    private final LlvmModuleLowerer llvmLowerer;
    private final LlvmTextEmitter llvmEmitter;
    private final ManagedZigLocator zigLocator;
    private final ZigBuildWriter buildWriter;
    private final ZigBuildInvoker buildInvoker;
    private final NativeSymbolInspector symbolInspector;
    private final J2llHomeResolver homeResolver;
    private final LlvmProtectionConfig protectionConfig;
    private final boolean methodTableHidingEnabled;
    private final boolean strip;
    private final NativeUnwindRetentionPolicy unwindRetentionPolicy;

    public ZigNativeLibraryBuilder() {
        this(new LlvmNameMangler());
    }

    public ZigNativeLibraryBuilder(LlvmNameMangler llvmNameMangler) {
        this(llvmNameMangler, false, 0L);
    }

    public ZigNativeLibraryBuilder(
            LlvmNameMangler llvmNameMangler,
            boolean callIndirectionEnabled,
            long protectionSeed) {
        this(llvmNameMangler, callIndirectionEnabled, protectionSeed, true);
    }

    public ZigNativeLibraryBuilder(
            LlvmNameMangler llvmNameMangler,
            boolean callIndirectionEnabled,
            long protectionSeed,
            boolean strip) {
        this(
                llvmNameMangler,
                LlvmProtectionConfig.selected(
                        protectionSeed,
                        false,
                        false,
                        false,
                        callIndirectionEnabled,
                        false),
                strip);
    }

    public ZigNativeLibraryBuilder(
            LlvmNameMangler llvmNameMangler,
            LlvmProtectionConfig protectionConfig,
            boolean strip) {
        this(llvmNameMangler, protectionConfig, false, strip);
    }

    public ZigNativeLibraryBuilder(
            LlvmNameMangler llvmNameMangler,
            LlvmProtectionConfig protectionConfig,
            boolean methodTableHidingEnabled,
            boolean strip) {
        this(
                llvmNameMangler,
                protectionConfig,
                methodTableHidingEnabled,
                strip,
                NativeUnwindRetentionPolicy.retaining());
    }

    public ZigNativeLibraryBuilder(
            LlvmNameMangler llvmNameMangler,
            LlvmProtectionConfig protectionConfig,
            boolean methodTableHidingEnabled,
            boolean strip,
            NativeUnwindRetentionPolicy unwindRetentionPolicy) {
        this(
                new HostJniCSourceGenerator(),
                new LlvmModuleLowerer(
                        llvmNameMangler,
                        businessStringSymbols(protectionConfig),
                        runtimeTokens(protectionConfig)),
                new LlvmTextEmitter(),
                new ManagedZigLocator(),
                new ZigBuildWriter(),
                new ZigBuildInvoker(),
                new NativeSymbolInspector(),
                new J2llHomeResolver(),
                protectionConfig,
                methodTableHidingEnabled,
                strip,
                unwindRetentionPolicy);
    }

    private static BusinessStringSymbolMapper businessStringSymbols(
            LlvmProtectionConfig protectionConfig) {
        NativeTextBuildKey buildKey =
                businessTextBuildKey(protectionConfig);
        return BusinessStringSymbolMapper.fromBytes(buildKey.bytes());
    }

    private static RuntimeTokenMapper runtimeTokens(
            LlvmProtectionConfig protectionConfig) {
        return RuntimeTokenMapper.fromBytes(
                nativeTextBuildKey(protectionConfig).bytes());
    }

    private static NativeTextBuildKey nativeTextBuildKey(
            LlvmProtectionConfig protectionConfig) {
        return NativeTextBuildKey.fromUtf8(
                "j2ll-zig-native-text-v1:"
                        + Long.toUnsignedString(protectionConfig.seed()));
    }

    private static NativeTextBuildKey registrationTextBuildKey(
            LlvmProtectionConfig protectionConfig) {
        return NativeTextBuildKey.fromUtf8(
                "j2ll-zig-registration-text-v1:"
                        + Long.toUnsignedString(protectionConfig.seed()));
    }

    private static NativeTextBuildKey businessTextBuildKey(
            LlvmProtectionConfig protectionConfig) {
        return NativeTextBuildKey.fromUtf8(
                "j2ll-zig-business-native-text-v1:"
                        + Long.toUnsignedString(protectionConfig.seed()));
    }

    public ZigNativeLibraryBuilder(
            HostJniCSourceGenerator sourceGenerator,
            LlvmModuleLowerer llvmLowerer,
            LlvmTextEmitter llvmEmitter,
            ManagedZigLocator zigLocator,
            ZigBuildWriter buildWriter,
            ZigBuildInvoker buildInvoker,
            NativeSymbolInspector symbolInspector,
            J2llHomeResolver homeResolver) {
        this(
                sourceGenerator,
                llvmLowerer,
                llvmEmitter,
                zigLocator,
                buildWriter,
                buildInvoker,
                symbolInspector,
                homeResolver,
                false,
                0L,
                true);
    }

    public ZigNativeLibraryBuilder(
            HostJniCSourceGenerator sourceGenerator,
            LlvmModuleLowerer llvmLowerer,
            LlvmTextEmitter llvmEmitter,
            ManagedZigLocator zigLocator,
            ZigBuildWriter buildWriter,
            ZigBuildInvoker buildInvoker,
            NativeSymbolInspector symbolInspector,
            J2llHomeResolver homeResolver,
            boolean callIndirectionEnabled,
            long protectionSeed) {
        this(
                sourceGenerator,
                llvmLowerer,
                llvmEmitter,
                zigLocator,
                buildWriter,
                buildInvoker,
                symbolInspector,
                homeResolver,
                callIndirectionEnabled,
                protectionSeed,
                true);
    }

    public ZigNativeLibraryBuilder(
            HostJniCSourceGenerator sourceGenerator,
            LlvmModuleLowerer llvmLowerer,
            LlvmTextEmitter llvmEmitter,
            ManagedZigLocator zigLocator,
            ZigBuildWriter buildWriter,
            ZigBuildInvoker buildInvoker,
            NativeSymbolInspector symbolInspector,
            J2llHomeResolver homeResolver,
            boolean callIndirectionEnabled,
            long protectionSeed,
            boolean strip) {
        this(
                sourceGenerator,
                llvmLowerer,
                llvmEmitter,
                zigLocator,
                buildWriter,
                buildInvoker,
                symbolInspector,
                homeResolver,
                LlvmProtectionConfig.selected(
                        protectionSeed,
                        false,
                        false,
                        false,
                        callIndirectionEnabled,
                        false),
                strip);
    }

    public ZigNativeLibraryBuilder(
            HostJniCSourceGenerator sourceGenerator,
            LlvmModuleLowerer llvmLowerer,
            LlvmTextEmitter llvmEmitter,
            ManagedZigLocator zigLocator,
            ZigBuildWriter buildWriter,
            ZigBuildInvoker buildInvoker,
            NativeSymbolInspector symbolInspector,
            J2llHomeResolver homeResolver,
            LlvmProtectionConfig protectionConfig,
            boolean strip) {
        this(
                sourceGenerator,
                llvmLowerer,
                llvmEmitter,
                zigLocator,
                buildWriter,
                buildInvoker,
                symbolInspector,
                homeResolver,
                protectionConfig,
                false,
                strip);
    }

    public ZigNativeLibraryBuilder(
            HostJniCSourceGenerator sourceGenerator,
            LlvmModuleLowerer llvmLowerer,
            LlvmTextEmitter llvmEmitter,
            ManagedZigLocator zigLocator,
            ZigBuildWriter buildWriter,
            ZigBuildInvoker buildInvoker,
            NativeSymbolInspector symbolInspector,
            J2llHomeResolver homeResolver,
            LlvmProtectionConfig protectionConfig,
            boolean methodTableHidingEnabled,
            boolean strip) {
        this(
                sourceGenerator,
                llvmLowerer,
                llvmEmitter,
                zigLocator,
                buildWriter,
                buildInvoker,
                symbolInspector,
                homeResolver,
                protectionConfig,
                methodTableHidingEnabled,
                strip,
                NativeUnwindRetentionPolicy.retaining());
    }

    public ZigNativeLibraryBuilder(
            HostJniCSourceGenerator sourceGenerator,
            LlvmModuleLowerer llvmLowerer,
            LlvmTextEmitter llvmEmitter,
            ManagedZigLocator zigLocator,
            ZigBuildWriter buildWriter,
            ZigBuildInvoker buildInvoker,
            NativeSymbolInspector symbolInspector,
            J2llHomeResolver homeResolver,
            LlvmProtectionConfig protectionConfig,
            boolean methodTableHidingEnabled,
            boolean strip,
            NativeUnwindRetentionPolicy unwindRetentionPolicy) {
        this.sourceGenerator = sourceGenerator;
        this.llvmLowerer = llvmLowerer;
        this.llvmEmitter = llvmEmitter;
        this.zigLocator = zigLocator;
        this.buildWriter = buildWriter;
        this.buildInvoker = buildInvoker;
        this.symbolInspector = symbolInspector;
        this.homeResolver = homeResolver;
        this.protectionConfig = Objects.requireNonNull(protectionConfig, "protectionConfig");
        this.methodTableHidingEnabled = methodTableHidingEnabled;
        this.strip = strip;
        this.unwindRetentionPolicy = Objects.requireNonNull(
                unwindRetentionPolicy,
                "unwindRetentionPolicy");
    }

    public Optional<ZigNativeBuildResult> build(
            Path workspaceRoot,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeBuildPlan buildPlan,
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods) throws IOException {
        return build(
                workspaceRoot,
                runtimeLoaderPlan,
                buildPlan,
                implementationPlan,
                irMethods,
                NativeBuildProgressListener.none());
    }

    public Optional<ZigNativeBuildResult> build(
            Path workspaceRoot,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeBuildPlan buildPlan,
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods,
            NativeBuildProgressListener progressListener) throws IOException {
        MethodTableHidingPlan methodTablePlan = new MethodTableHidingPlanner().plan(
                implementationPlan.registrationPlan(),
                methodTableHidingEnabled,
                protectionConfig.seed());
        return build(
                workspaceRoot,
                runtimeLoaderPlan,
                buildPlan,
                implementationPlan,
                irMethods,
                progressListener,
                methodTablePlan);
    }

    public Optional<ZigNativeBuildResult> build(
            Path workspaceRoot,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeBuildPlan buildPlan,
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods,
            NativeBuildProgressListener progressListener,
            MethodTableHidingPlan methodTablePlan) throws IOException {
        NativeLlvmCompilation llvmCompilation = new NativeLlvmCompiler(
                        llvmLowerer,
                        llvmEmitter)
                .compile(implementationPlan, irMethods, protectionConfig);
        return build(
                workspaceRoot,
                runtimeLoaderPlan,
                buildPlan,
                implementationPlan,
                irMethods,
                progressListener,
                methodTablePlan,
                llvmCompilation);
    }

    public Optional<ZigNativeBuildResult> build(
            Path workspaceRoot,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeBuildPlan buildPlan,
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods,
            NativeBuildProgressListener progressListener,
            MethodTableHidingPlan methodTablePlan,
            NativeLlvmCompilation llvmCompilation) throws IOException {
        return build(
                workspaceRoot,
                runtimeLoaderPlan,
                buildPlan,
                implementationPlan,
                irMethods,
                progressListener,
                methodTablePlan,
                llvmCompilation,
                nativeTextBuildKey(protectionConfig),
                businessTextBuildKey(protectionConfig),
                registrationTextBuildKey(protectionConfig));
    }

    public Optional<ZigNativeBuildResult> build(
            Path workspaceRoot,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeBuildPlan buildPlan,
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods,
            NativeBuildProgressListener progressListener,
            MethodTableHidingPlan methodTablePlan,
            NativeLlvmCompilation llvmCompilation,
            NativeTextBuildKey nativeTextBuildKey) throws IOException {
        return build(
                workspaceRoot,
                runtimeLoaderPlan,
                buildPlan,
                implementationPlan,
                irMethods,
                progressListener,
                methodTablePlan,
                llvmCompilation,
                nativeTextBuildKey,
                nativeTextBuildKey,
                nativeTextBuildKey);
    }

    public Optional<ZigNativeBuildResult> build(
            Path workspaceRoot,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeBuildPlan buildPlan,
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods,
            NativeBuildProgressListener progressListener,
            MethodTableHidingPlan methodTablePlan,
            NativeLlvmCompilation llvmCompilation,
            NativeTextBuildKey nativeTextBuildKey,
            NativeTextBuildKey registrationBuildKey) throws IOException {
        return build(
                workspaceRoot,
                runtimeLoaderPlan,
                buildPlan,
                implementationPlan,
                irMethods,
                progressListener,
                methodTablePlan,
                llvmCompilation,
                nativeTextBuildKey,
                nativeTextBuildKey,
                registrationBuildKey);
    }

    public Optional<ZigNativeBuildResult> build(
            Path workspaceRoot,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeBuildPlan buildPlan,
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods,
            NativeBuildProgressListener progressListener,
            MethodTableHidingPlan methodTablePlan,
            NativeLlvmCompilation llvmCompilation,
            NativeTextBuildKey nativeTextBuildKey,
            NativeTextBuildKey businessTextBuildKey,
            NativeTextBuildKey registrationBuildKey) throws IOException {
        Objects.requireNonNull(progressListener, "progressListener");
        Objects.requireNonNull(methodTablePlan, "methodTablePlan");
        Objects.requireNonNull(llvmCompilation, "llvmCompilation");
        Objects.requireNonNull(nativeTextBuildKey, "nativeTextBuildKey");
        Objects.requireNonNull(
                businessTextBuildKey,
                "businessTextBuildKey");
        Objects.requireNonNull(
                registrationBuildKey,
                "registrationBuildKey");
        if (implementationPlan.implementations().isEmpty() || buildPlan.units().isEmpty()) {
            return Optional.empty();
        }
        String expectedLlvmInputKey =
                NativeLlvmCompiler.inputKey(implementationPlan, irMethods, protectionConfig);
        if (!llvmCompilation.inputKey().equals(expectedLlvmInputKey)) {
            throw new IOException(
                    "precompiled LLVM input does not match the final native implementation plan");
        }
        progressListener.managedZigPreparationStarted();
        ManagedZig zig = zigLocator.ensure(homeResolver.resolve());
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(workspaceRoot);
        prepareDirectories(workspace);
        String libraryName = buildPlan.units().get(0).libraryName();
        Path wrapper = writeJniWrapper(
                workspace,
                libraryName,
                runtimeLoaderPlan,
                implementationPlan,
                methodTablePlan,
                nativeTextBuildKey,
                businessTextBuildKey,
                registrationBuildKey,
                RuntimeHelperReachabilityPlan.from(
                        llvmCompilation),
                progressListener);
        NativeLlvmSourcePlan llvmSources = writeLlvmSources(
                workspace,
                llvmCompilation,
                progressListener);
        progressListener.preparationProgress(new NativePreparationProgress(
                NativePreparationStep.PREPARE_ZIG_BUILD,
                0L,
                4L,
                "native runtime"));
        Path runtime = workspace.runtimeDirectory().resolve("j2ll_runtime_helpers.c");
        Files.writeString(
                runtime,
                new HostWindowsDllEntryRuntimeSource().emit(libraryName),
                StandardCharsets.UTF_8);
        NativeLibcRequirementPlan libcRequirement =
                NativeLibcRequirementPlan.inspectAll(List.of(
                        Files.readString(wrapper, StandardCharsets.UTF_8),
                        Files.readString(runtime, StandardCharsets.UTF_8)));
        progressListener.preparationProgress(new NativePreparationProgress(
                NativePreparationStep.PREPARE_ZIG_BUILD,
                1L,
                4L,
                "native runtime"));
        List<Path> headers = new ZigJniHeaderSet().prepare(
                workspace,
                libcRequirement);
        progressListener.preparationProgress(new NativePreparationProgress(
                NativePreparationStep.PREPARE_ZIG_BUILD,
                2L,
                4L,
                "JNI headers"));
        ZigSourceSet sources = new ZigSourceSet(
                llvmSources.retainedPaths(),
                List.of(wrapper, runtime),
                List.of(),
                headers,
                libcRequirement,
                llvmSources);
        buildWriter.write(
                workspace,
                libraryName,
                buildPlan,
                new ZigInputSet(sources),
                strip,
                unwindRetentionPolicy);
        progressListener.preparationProgress(new NativePreparationProgress(
                NativePreparationStep.PREPARE_ZIG_BUILD,
                3L,
                4L,
                "build graph"));
        ZigBuildInvocation invocation = buildInvoker.invocation(zig, workspace);
        progressListener.preparationProgress(new NativePreparationProgress(
                NativePreparationStep.PREPARE_ZIG_BUILD,
                4L,
                4L,
                "done"));
        try {
            buildInvoker.invoke(zig, workspace, buildPlan, sources, progressListener);
        } catch (IOException exception) {
            throw ZigBuildException.from(buildPlan, workspace, exception);
        }
        List<NativeLibraryArtifact> artifacts = collectArtifacts(
                runtimeLoaderPlan.embeddedLibraryDirectory(),
                buildPlan,
                wrapper,
                sources);
        return Optional.of(new ZigNativeBuildResult(
                zig,
                workspace.buildZig(),
                workspace.manifest(),
                wrapper,
                artifacts,
                invocation));
    }

    Path writeJniWrapper(
            ZigBuildWorkspace workspace,
            String libraryName,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeImplementationPlan implementationPlan,
            MethodTableHidingPlan methodTablePlan) throws IOException {
        return writeJniWrapper(
                workspace,
                libraryName,
                runtimeLoaderPlan,
                implementationPlan,
                methodTablePlan,
                nativeTextBuildKey(protectionConfig),
                businessTextBuildKey(protectionConfig),
                registrationTextBuildKey(protectionConfig));
    }

    Path writeJniWrapper(
            ZigBuildWorkspace workspace,
            String libraryName,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeImplementationPlan implementationPlan,
            MethodTableHidingPlan methodTablePlan,
            NativeTextBuildKey nativeTextBuildKey) throws IOException {
        return writeJniWrapper(
                workspace,
                libraryName,
                runtimeLoaderPlan,
                implementationPlan,
                methodTablePlan,
                nativeTextBuildKey,
                nativeTextBuildKey,
                nativeTextBuildKey);
    }

    Path writeJniWrapper(
            ZigBuildWorkspace workspace,
            String libraryName,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeImplementationPlan implementationPlan,
            MethodTableHidingPlan methodTablePlan,
            NativeTextBuildKey nativeTextBuildKey,
            NativeTextBuildKey registrationBuildKey) throws IOException {
        return writeJniWrapper(
                workspace,
                libraryName,
                runtimeLoaderPlan,
                implementationPlan,
                methodTablePlan,
                nativeTextBuildKey,
                nativeTextBuildKey,
                registrationBuildKey);
    }

    Path writeJniWrapper(
            ZigBuildWorkspace workspace,
            String libraryName,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeImplementationPlan implementationPlan,
            MethodTableHidingPlan methodTablePlan,
            NativeTextBuildKey nativeTextBuildKey,
            NativeTextBuildKey businessTextBuildKey,
            NativeTextBuildKey registrationBuildKey) throws IOException {
        return writeJniWrapper(
                workspace,
                libraryName,
                runtimeLoaderPlan,
                implementationPlan,
                methodTablePlan,
                nativeTextBuildKey,
                businessTextBuildKey,
                registrationBuildKey,
                RuntimeHelperReachabilityPlan.conservative(),
                NativeBuildProgressListener.none());
    }

    private Path writeJniWrapper(
            ZigBuildWorkspace workspace,
            String libraryName,
            RuntimeLoaderPlan runtimeLoaderPlan,
            NativeImplementationPlan implementationPlan,
            MethodTableHidingPlan methodTablePlan,
            NativeTextBuildKey nativeTextBuildKey,
            NativeTextBuildKey businessTextBuildKey,
            NativeTextBuildKey registrationBuildKey,
            RuntimeHelperReachabilityPlan runtimeReachability,
            NativeBuildProgressListener progressListener)
            throws IOException {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(runtimeLoaderPlan, "runtimeLoaderPlan");
        Objects.requireNonNull(implementationPlan, "implementationPlan");
        Objects.requireNonNull(methodTablePlan, "methodTablePlan");
        Objects.requireNonNull(nativeTextBuildKey, "nativeTextBuildKey");
        Objects.requireNonNull(
                businessTextBuildKey,
                "businessTextBuildKey");
        Objects.requireNonNull(
                registrationBuildKey,
                "registrationBuildKey");
        Objects.requireNonNull(
                runtimeReachability,
                "runtimeReachability");
        Objects.requireNonNull(progressListener, "progressListener");
        if (!NativeLibraryName.isSafe(libraryName)) {
            throw new IOException("unsafe native library name in build plan: " + libraryName);
        }
        Path jniDirectory = workspace.jniDirectory().toAbsolutePath().normalize();
        Files.createDirectories(jniDirectory);
        Path wrapper = jniDirectory.resolve(libraryName + ".c").normalize();
        if (!wrapper.startsWith(jniDirectory)) {
            throw new IOException("native wrapper path escapes the Zig JNI workspace: " + wrapper);
        }
        Files.writeString(
                wrapper,
                sourceGenerator.generate(
                        implementationPlan,
                        runtimeLoaderPlan,
                        methodTablePlan,
                        nativeTextBuildKey,
                        businessTextBuildKey,
                        registrationBuildKey,
                        runtimeReachability,
                        progressListener),
                StandardCharsets.UTF_8);
        return wrapper;
    }

    private void prepareDirectories(ZigBuildWorkspace workspace) throws IOException {
        Files.createDirectories(workspace.llvmDirectory());
        Files.createDirectories(workspace.jniDirectory());
        Files.createDirectories(workspace.runtimeDirectory());
        Files.createDirectories(workspace.logsDirectory());
    }

    private NativeLlvmSourcePlan writeLlvmSources(
            ZigBuildWorkspace workspace,
            NativeLlvmCompilation compilation,
            NativeBuildProgressListener progressListener) throws IOException {
        int total = compilation.modules().size();
        progressListener.preparationProgress(new NativePreparationProgress(
                NativePreparationStep.WRITE_NATIVE_IR,
                0L,
                total,
                total == 0 ? "no LLVM modules" : "waiting"));
        ArrayList<NativeLlvmSource> sources = new ArrayList<>();
        Path omissionDirectory = workspace.llvmDirectory().resolve("no-unwind");
        int completed = 0;
        for (NativeLlvmModuleCompilation module : compilation.modules()) {
            Path llvmPath = workspace.llvmDirectory()
                    .resolve(NativeSourceName.llvmFileName(module.owner()));
            Files.writeString(llvmPath, module.llvmText(), StandardCharsets.UTF_8);
            Optional<Path> omissionPath = Optional.empty();
            if (module.llvmTextWithoutUnwind().isPresent()) {
                Files.createDirectories(omissionDirectory);
                Path path = omissionDirectory
                        .resolve(NativeSourceName.llvmFileName(module.owner()));
                Files.writeString(
                        path,
                        module.llvmTextWithoutUnwind().orElseThrow(),
                        StandardCharsets.UTF_8);
                omissionPath = Optional.of(path);
            }
            sources.add(new NativeLlvmSource(
                    module.owner(),
                    llvmPath,
                    omissionPath,
                    module.emissionPlan().proof().omissionSafe(),
                    module.emissionPlan().proof().reasonCode()));
            completed++;
            progressListener.preparationProgress(new NativePreparationProgress(
                    NativePreparationStep.WRITE_NATIVE_IR,
                    completed,
                    total,
                    completed == total ? "done" : module.owner()));
        }
        return new NativeLlvmSourcePlan(sources);
    }

    private List<NativeLibraryArtifact> collectArtifacts(
            String embeddedLibraryDirectory,
            NativeBuildPlan buildPlan,
            Path wrapper,
            ZigSourceSet sources) throws IOException {
        EmbeddedLibraryLayout layout = new EmbeddedLibraryLayout();
        NativeUnwindSectionInspector unwindInspector =
                new NativeUnwindSectionInspector();
        NativeUnwindArtifactVerifier unwindVerifier =
                new NativeUnwindArtifactVerifier();
        ArrayList<NativeLibraryArtifact> artifacts = new ArrayList<>();
        for (NativeBuildUnit unit : buildPlan.units()) {
            if (!Files.exists(unit.outputPath())) {
                throw new IOException("managed Zig did not produce selected target artifact: " + unit.outputPath());
            }
            NativeLlvmUnwindTargetSummary unwindSummary =
                    sources.llvmUnwindSources().summarize(
                            unwindRetentionPolicy.resolve(unit.target()),
                            sources.objectInputs().size());
            NativeUnwindSectionInspection unwindInspection =
                    unwindInspector.inspect(unit.target(), unit.outputPath());
            unwindVerifier.verify(
                    unwindSummary,
                    unwindInspection,
                    unit.outputPath());
            artifacts.add(new NativeLibraryArtifact(
                    unit.target(),
                    unit.outputPath(),
                    wrapper,
                    layout.jarPath(embeddedLibraryDirectory, unit.target()),
                    sha256(unit.outputPath()),
                    symbolInspector.exportedSymbols(unit.target(), unit.outputPath()),
                    Optional.of(unwindInspection)));
        }
        return List.copyOf(artifacts);
    }

    private String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }
}
