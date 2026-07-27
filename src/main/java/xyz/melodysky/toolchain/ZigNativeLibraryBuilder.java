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
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.packaging.EmbeddedLibraryLayout;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.toolchain.symbols.NativeSymbolInspector;

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
                new HostJniCSourceGenerator(),
                new LlvmModuleLowerer(llvmNameMangler),
                new LlvmTextEmitter(),
                new ManagedZigLocator(),
                new ZigBuildWriter(),
                new ZigBuildInvoker(),
                new NativeSymbolInspector(),
                new J2llHomeResolver(),
                protectionConfig,
                methodTableHidingEnabled,
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
        Objects.requireNonNull(progressListener, "progressListener");
        Objects.requireNonNull(methodTablePlan, "methodTablePlan");
        Objects.requireNonNull(llvmCompilation, "llvmCompilation");
        if (implementationPlan.implementations().isEmpty() || buildPlan.units().isEmpty()) {
            return Optional.empty();
        }
        String expectedLlvmInputKey =
                NativeLlvmCompiler.inputKey(implementationPlan, irMethods, protectionConfig);
        if (!llvmCompilation.inputKey().equals(expectedLlvmInputKey)) {
            throw new IOException(
                    "precompiled LLVM input does not match the final native implementation plan");
        }
        ManagedZig zig = zigLocator.ensure(homeResolver.resolve());
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(workspaceRoot);
        prepareDirectories(workspace);
        String libraryName = buildPlan.units().get(0).libraryName();
        Path wrapper = writeJniWrapper(
                workspace,
                libraryName,
                runtimeLoaderPlan,
                implementationPlan,
                methodTablePlan);
        Path runtime = workspace.runtimeDirectory().resolve("j2ll_runtime_helpers.c");
        Files.writeString(runtime, "/* runtime helper C inputs are helper-backed skeletons in this slice */\n", StandardCharsets.UTF_8);
        List<Path> llvmSources = writeLlvmSources(workspace, llvmCompilation);
        ZigSourceSet sources = new ZigSourceSet(
                llvmSources,
                List.of(wrapper, runtime),
                List.of(),
                new ZigJniHeaderSet().prepare(workspace));
        buildWriter.write(
                workspace,
                libraryName,
                buildPlan,
                new ZigInputSet(sources),
                strip);
        ZigBuildInvocation invocation = buildInvoker.invocation(zig, workspace);
        try {
            buildInvoker.invoke(zig, workspace, buildPlan, sources, progressListener);
        } catch (IOException exception) {
            throw ZigBuildException.from(buildPlan, workspace, exception);
        }
        List<NativeLibraryArtifact> artifacts = collectArtifacts(
                runtimeLoaderPlan.embeddedLibraryDirectory(),
                buildPlan,
                wrapper);
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
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(runtimeLoaderPlan, "runtimeLoaderPlan");
        Objects.requireNonNull(implementationPlan, "implementationPlan");
        Objects.requireNonNull(methodTablePlan, "methodTablePlan");
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
                        methodTablePlan),
                StandardCharsets.UTF_8);
        return wrapper;
    }

    private void prepareDirectories(ZigBuildWorkspace workspace) throws IOException {
        Files.createDirectories(workspace.llvmDirectory());
        Files.createDirectories(workspace.jniDirectory());
        Files.createDirectories(workspace.runtimeDirectory());
        Files.createDirectories(workspace.logsDirectory());
    }

    private List<Path> writeLlvmSources(
            ZigBuildWorkspace workspace,
            NativeLlvmCompilation compilation) throws IOException {
        ArrayList<Path> sources = new ArrayList<>();
        for (NativeLlvmModuleCompilation module : compilation.modules()) {
            Path llvmPath = workspace.llvmDirectory()
                    .resolve(NativeSourceName.llvmFileName(module.owner()));
            Files.writeString(llvmPath, module.llvmText(), StandardCharsets.UTF_8);
            sources.add(llvmPath);
        }
        return List.copyOf(sources);
    }

    private List<NativeLibraryArtifact> collectArtifacts(
            String embeddedLibraryDirectory,
            NativeBuildPlan buildPlan,
            Path wrapper) throws IOException {
        EmbeddedLibraryLayout layout = new EmbeddedLibraryLayout();
        ArrayList<NativeLibraryArtifact> artifacts = new ArrayList<>();
        for (NativeBuildUnit unit : buildPlan.units()) {
            if (!Files.exists(unit.outputPath())) {
                throw new IOException("managed Zig did not produce selected target artifact: " + unit.outputPath());
            }
            artifacts.add(new NativeLibraryArtifact(
                    unit.target(),
                    unit.outputPath(),
                    wrapper,
                    layout.jarPath(embeddedLibraryDirectory, unit.target()),
                    sha256(unit.outputPath()),
                    symbolInspector.exportedSymbols(unit.target(), unit.outputPath())));
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
